# MiniDB Web 集成摸底笔记（Phase 0）

> 本文是 MiniDB Studio（Web 工作台）开发的内核集成备忘：真实内核暴露了什么、
> Web 层如何复用、哪里需要新增**只读观察接口/适配器**、哪里有已知缺口。
> 遵循计划书约束：优先调用现有公开接口；新增能力按 Adapter → Service → 核心类的顺序。

## 1. 构建与运行环境

- `java/mvn` 不在 PATH，必须先 export（tools/ 已 vendored，被 .gitignore 忽略）：
  ```bash
  export JAVA_HOME='D:\MiniDB\tools\jdk-17.0.20.1+1'
  export PATH="/d/MiniDB/tools/jdk-17.0.20.1+1/bin:/d/MiniDB/tools/apache-maven-3.9.16/bin:$PATH"
  mvn -q -s tools/mvn-settings.xml test
  ```
- Maven 走阿里云镜像（tools/mvn-settings.xml），可解析新依赖。
- 基线：331 项测试全绿（截至 WAL bug 修复后）。任何改动后全量回归。
- pom 已新增 `jackson-databind 2.17.2`（Web 层 JSON 序列化，唯一新增依赖）。

## 2. 模块地图（src/main/java/minidb）

| 包 | 职责 | Web 层使用方式 |
|---|---|---|
| `storage` | Database 门面 / StorageEngine / BufferPool / DiskManager / Table(Fixed/Var) / Catalog / Schema / RowCodec / Page / IndexMeta | 直接调用公开 API |
| `btree` | BPlusTree + BTreeNode（节点结构包私有） | 快照需在 btree 包内加只读适配 |
| `sql` | Lexer / Parser / Token / Ast | 脚本拆分用真 Lexer（禁止 split(";")） |
| `exec` | Executor（火山执行器 + 极简优化器）/ Operators 各算子 | SQL 入口；RID 浏览需新增公开方法 |
| `txn` | TxnSession / LockManager（严格2PL + 等待图死锁检测） | 每个浏览器会话一个 Executor + TxnSession |
| `wal` | WalLog（逻辑日志）/ Recovery（redo+undo 简化 ARIES） | 恢复实验直接复用 |
| `jdbc` | MiniDbDriver（jdbc:minidb:路径）/ Connection / Statement / PreparedStatement | JDBC 实验直接用，配独立实验库 |
| `repl` | Repl（.open/.create 等命令） | 不动，保持兼容 |
| `demo` | JdbcDemo | 不动 |

## 3. 关键内核 API 事实

### 3.1 Database 生命周期（storage/Database.java）
- `Database.open(Path)` / `open(Path, bufferPages=4096)`：构造时加载 Catalog、打开所有表与索引、
  打开 WAL，**若有残留日志自动执行 Recovery（redo+undo）→ truncate → flush**。
- `close()` = checkpoint：flush + WAL truncate + 关闭。**同一路径必须全 JVM 单实例**
  （文件句柄 + 缓冲池语义，双开必坏）→ Web 层需要 DatabaseRegistry 以绝对路径为键做单例 + 引用计数。
- `crash()`：模拟断电——WAL `abruptClose()`（不 fsync）+ engine `abruptClose()`（缓冲池不刷盘）。
  崩溃实验：crash 后重新 `Database.open()` 即触发真实恢复。**不可用 close() 冒充 crash。**
- 全部表/索引操作方法都是 `synchronized`（表级互斥），线程安全性可接受；
  SELECT 走 Executor 的扫描（BufferPool.getPage 是 synchronized）。
- DDL（建表/删表/建索引/删索引）**不走事务日志**，createTable 里直接 `engine.flush()`。

### 3.2 Executor（exec/Executor.java）
- `new Executor(db)`；`Result = record(List<String> columns, List<Object[]> rows, String message)`。
- `execute(String sql)`：**REPL 语义**——内部维护 activeSession，BEGIN/COMMIT/ROLLBACK 被它吃掉。
  Web 层**不用**这条路径管事务；用：
  - `execute(Object stmt | String sql, TxnSession session)`：显式会话执行（session=null 则语句级 autoCommit）；
  - `begin(Isolation.READ_COMMITTED|REPEATABLE_READ)` / `commit(s)` / `rollback(s)`；
  - 事务按钮与 SQL 的 BEGIN/COMMIT/ROLLBACK 必须共用同一 TxnSession：
    Web 层统一走 `begin/commit/rollback` API，SQL 文本里的 BEGIN/COMMIT/ROLLBACK 由
    Web 层拦翻译到同一会话（Executor.execute(String) 的内部状态不可见，绝不能混用两条状态机）。
- `explain(String sql)` → 缩进文本计划树（真实 Optimizer 输出）；仅支持 SELECT。
- 值类型域：INT→Integer、BIGINT→Long、DOUBLE→Double、VARCHAR→String、NULL→null。
  **BIGINT 必须 JSON 字符串化**防 JS 精度丢失。
- 列名形态：扫描输出 `alias.col`；SELECT 带别名/表达式时用别名或表达式文本。
- 内部已有但**包私有/私有**的关键机制（RID 写路径复用点）：
  - `insertWithLog(session, table, values)`：日志临界区内 {改页 + WAL + pageLsn} + 行锁 + undo 栈 + 新页追踪；
  - runUpdate/runDelete 内嵌同款机制；`maintainIndexesOnInsert/Delete` 维护索引。
  → Web 的 RID 编辑（新增/编辑/删除记录）**必须复用这套机制**（WAL/锁/undo/索引全都要），
  方案：在 Executor 增加少量 public 包装方法（见 §5）。
- `Executor.browse(...)`（新增，见 §5）：RID 感知分页浏览。

### 3.3 表与记录（storage/Table.java, Rid, Schema）
- `Table`：`insert(Object[])→Rid`、`get(Rid)`、`delete(Rid)`、`update(Rid, newRow)→Rid`（变长表放不下会迁移返回新 RID）、
  `restoreAt(Rid, row)`（WAL undo 用）、`scan()→Iterator<Row>`（页链+槽序）、`rowCount()`（O(1) 计数器）、`pageCount()`。
- `Rid = (pageId, slot)`，JSON 表示 `"p/slot"` 或对象；编辑失效 RID → `get` 抛 RECORD → 映射 RID_STALE。
- `Schema(tableName, List<Column>)`；`Column(name, type, maxLength)`；类型 INT/BIGINT/DOUBLE/VARCHAR，
  `Column.MAX_VARCHAR=3000`；`schema.fixedLength()` 决定定长/变长表。
- 定长表 NULL：RowCodec 用 null 位图（需确认，RowCodec.encode/decode 已有测试覆盖）。

### 3.4 索引（Database.createIndex/dropIndex/indexesFor, IndexMeta, IndexKeys）
- `createIndex(name, table, column)`：全表扫描建树，**遇到重复值抛异常（唯一索引）**；
  列仅支持 INT/BIGINT/VARCHAR(≤255 字节)；NULL 不入索引。
- `IndexMeta(name, column, rootPage, keyType: LONG|STRING)`；目录持久化。
- **已知缺口**：`Executor.maintainIndexesOnInsert/OnUpdate` 忽略 `IndexKeys.insert` 返回值——
  建索引后的后续 INSERT/UPDATE **不强制唯一**。计划书要求 UNIQUE_CONSTRAINT_VIOLATION 错误码，
  需在 Executor 层补检查（见 §5 修改清单 #1）。
- 索引名全局唯一（不按表隔离）。

### 3.5 事务与锁（txn/）
- `TxnSession(txnId, isolation, autoCommit, locks, pool[, readOnly])`；字段 public：
  `heldLocks`、`undoActions`、`finished`、`newPages`。
- 行锁键 `table/pageId/slot`；`lockRow(table, rid, immediateRelease)`；严格 2PL，commit/rollback 时 releaseAll。
- `LockManager`：S/X 兼容矩阵、FIFO 等待、等待图 DFS 死锁检测（抛 DEADLOCK，请求者为牺牲者）、
  60s 等锁超时（也抛 DEADLOCK）。**锁状态全部私有** → 快照需在 LockManager 加只读 `snapshot()`（§5 #2）。
- 隔离级别只有 READ_COMMITTED / REPEATABLE_READ（语义见 TxnIsolationTest：RC 读不锁、RR 用 S 锁）。

### 3.6 WAL 与恢复（wal/）
- `WalLog.Rec(lsn, type, txnId, table, pageId, slot, before, after)`；type: BEGIN1/INSERT2/DELETE3/UPDATE4/COMMIT5/ABORT6。
- `readAll()` 读全部记录；`sync()` fsync；`truncate()` checkpoint；`crash()` 用 `abruptClose()`。
- `Recovery.recover(db, records)` 静态三步：分析（按 txnId 分组收集 committed）→ redo（LSN 序条件幂等重放）→
  undo（未提交事务逆序）。**无恢复报告结构** → 实验需要真实动作序列：给 Recovery 加可选报告收集器重载（§5 #3），
  保持原签名兼容。
- 恢复实验流程（真实）：实验库执行事务（一个提交、一个不提交）→ `db.crash()` → 重开 Database
  （构造器自动恢复）→ 读取恢复报告展示 redo/undo → 数据一致性断言。
  注意：**独立实验库 + 独立 Java 子进程**是计划书要求，Studio 的实现：实验库文件独立 + crash 由专用
  子进程或主进程内执行（首版在主进程内、用独立文件；文档说明与子进程等价性：abruptClose 语义一致）。

### 3.7 缓冲池与页（storage/BufferPool, Page, TablePageHeader）
- `BufferPool`：capacity/size/hits/misses/evictions/writebacks + `getPage/unpin/flushPage/flushAll`。
  **页的 pin/dirty/pageLsn 状态私有** → 快照需加只读 `snapshot()`（§5 #2）。
- `Page.SIZE=4096`；`Page.type()` 可判页类型；`TablePageHeader` 全部 getter 是 public static：
  numSlots/freeLow/dataStart/totalFree/next/prev/pageLsn——存储实验可经 `pool.withPage(pid, false, ...)` 读。
- `StorageEngine.pageCount()/bitmapCount()/disk().filePages()` 可用。

### 3.8 B+ 树（btree/）
- 公开：`rootPage()/height()/stats()/validate()/search/insert/delete/rangeScan/scanAll/drop()`。
- `BTreeStats(height, internalNodes, leafNodes, ...)` 已有。
- **节点级结构（keys/children/siblings）在 BTreeNode 内部，包私有** → 只读快照需在 btree 包内新增
  `BPlusTree.snapshot()`（§5 #4），返回不可变 DTO（root/height/nodes[{pageId,kind,keys,children/next/prev,usage}]）。
  前端只做 Snapshot → SVG，绝不自己解释文件。

### 3.9 SQL 词法/解析（sql/）
- `Lexer(src).tokenize() → List<Token(type, text, pos)>`——**脚本拆分**：按 SEMI token 切分即可正确处理
  引号内的 `;`（真词法）。Token.pos 是字符偏移 → 行/列换算由 Web 层做。
- `Parser.parse(sql)` 抛 `[PARSE] msg (位置 pos)`；MiniDbException.Code → API 错误码映射见 §6。
- 语句 AST：SelectStmt/CreateTableStmt(ColumnDef name,type,size)/DropTableStmt/CreateIndexStmt/
  DropIndexStmt/InsertStmt/UpdateStmt/DeleteStmt + BEGIN/COMMIT/ROLLBACK（以 Token.Type 返回）。
- **无 PRIMARY KEY/约束语义**（Token 有 PRIMARY/KEY 但 Parser 未实现约束）→ 建表 UI 不提供假 PK，
  唯一性通过真实唯一索引实现。

### 3.10 JDBC（jdbc/）
- `jdbc:minidb:<path>`；META-INF/services 已注册；`MiniDbConnection` 内部 `Database.open`。
- **JDBC 实验必须用独立实验库文件**（驱动会另开 Database 实例，与工作库单例冲突）。
- SQL 方言经真实 Parser（支持 ? 占位符的 PreparedStatement）。

## 4. 会话与并发模型（Web 层设计决定）

```
DatabaseRegistry: path(绝对、正规化) → Database 单例 + 引用计数（避免双开同一文件）
SessionManager:   sessionId → WebSession { Executor, activeTxn, isolation, dbPath, lastTouch }
```

- 每个浏览器会话独立 Executor + 独立 TxnSession（满足 §15）。
- 事务按钮与 SQL 的 BEGIN/COMMIT/ROLLBACK 共享 WebSession.activeTxn（满足 §13）。
- 空闲 15 分钟：调度线程扫描 → rollback 活动事务 → 释放 Database 引用 → 下次请求报 SESSION_EXPIRED（§16）。
- 浏览器刷新：前端持 sessionId（localStorage）→ GET /api/v1/session 恢复状态（§43）。
- SQL 文本里的 BEGIN/COMMIT/ROLLBACK 与按钮共用 activeTxn；显式事务期间前端禁用 DDL 面板（§14，
  后端在显式事务中收到 DDL 语句也返回 TRANSACTION_ACTIVE 错误——DDL 不走日志、无法回滚）。

## 5. 内核修改清单（全部增量式，均有测试护航）

| # | 位置 | 修改 | 理由（现有接口为何不够） |
|---|---|---|---|
| 1 | exec/Executor | `maintainIndexesOnInsert`（及 UPDATE 索引维护）检查 `IndexKeys.insert` 返回值，重复时抛 `RECORD`（映射 UNIQUE_CONSTRAINT_VIOLATION），由事务回滚机制清理已插入行 | 计划书要求唯一索引真实生效；当前 INSERT 不查重（建索引时才查） |
| 2 | txn/LockManager, storage/BufferPool | 新增只读 `snapshot()` 方法（不改任何行为） | 锁等待图/缓冲池可视化需要结构化状态；字段全私有，外部无法读取 |
| 3 | wal/Recovery | 新增 `recover(db, records, RecoveryReport)` 重载（原两参签名委托新重载，报告传 null 行为不变） | 恢复实验需展示真实 redo/undo 动作序列 |
| 4 | btree/BPlusTree | 新增 `snapshot()` 只读方法（在 btree 包内读 BTreeNode，返回不可变 DTO） | B+ 树可视化需要节点结构；BTreeNode 包私有 |
| 5 | exec/Executor | 新增 public：`browse(table, whereExpr, sortCol, desc, offset, limit)`（RID 感知分页，内核侧分页/排序）；`insertRow/updateRowByRid/deleteRowByRid`（复用 insertWithLog 等私有机制） | 数据浏览器需要 RID + 内核分页；绕过这些机制会丢失 WAL/锁/索引/undo 语义 |
| 6 | exec/Executor | （可选）`parseAst(sql)` 暴露 AST 供 SQL 执行流程实验 | Parser.parse 已 public，仅包装 |

文件格式：**不变**。REPL/JDBC：不受影响（#1 使唯一索引语义在 REPL/JDBC 同样生效，属行为修复）。

## 6. 错误码映射（MiniDbException.Code → API code）

| 内核 Code | API code |
|---|---|
| IO | DATABASE_NOT_FOUND / IO |
| PAGE_INVALID / BUFFER_FULL | PAGE_INVALID / BUFFER_FULL |
| SCHEMA | SCHEMA_ERROR |
| RECORD | RECORD_ERROR / UNIQUE_CONSTRAINT_VIOLATION（唯一冲突文案）/ RID_STALE（RID 编辑场景） |
| CATALOG | DATABASE_NOT_OPEN / TABLE_NOT_FOUND / INDEX_NOT_FOUND（按消息细分） |
| BTREE | BTREE_ERROR |
| PARSE | SQL_PARSE_ERROR |
| EXEC | SQL_EXECUTION_ERROR |
| TXN | TRANSACTION_REQUIRED / TRANSACTION_ACTIVE |
| DEADLOCK / LOCK | DEADLOCK / LOCK_ERROR |
| WAL | WAL_ERROR |
| JDBC | JDBC_ERROR |
| （Web 层自产） | SESSION_EXPIRED / RESULT_TRUNCATED / EXPERIMENT_FAILED / VALIDATION_ERROR |

## 7. 兼容性红线（每阶段验收项）

- `mvn test` 331 项全绿不退化；
- REPL（`minidb.repl.Repl`）、JDBC（`jdbc:minidb:` + JdbcDemo）、数据文件格式、既有示例不动；
- Web 只做增量适配；不为 UI 伪造内核结果（所有快照/计划/耗时来自真实内核）。

## 8. 已确认可复用的实验素材

- 崩溃恢复：`Database.crash()` + 构造期自动 Recovery + `WalLog.readAll()` + 新 RecoveryReport。
- 缓冲池命中/淘汰/写回计数：BufferPool counters（.stats REPL 命令同源）。
- 死锁：两个会话交叉加行锁 → LockManager 抛 DEADLOCK（LockManagerTest 已验证模式）。
- 索引性能：同表建索引前后执行同款 SELECT，对比 explain() 计划与耗时（PERF.md 为历史结果，需标注）。
- SQL 流程：Lexer tokens → Parser AST → Executor.explain() 逻辑/物理计划 → 结果（全部真实）。
