# MiniDB —— 从零手写的关系型数据库

纯 Java（JDK 17）+ Maven + JUnit 5 实现的教学向关系型数据库内核，严格按照《任务书》自底向上分阶段开发。**每个阶段结束时都能在 REPL 里真跑通**，全部代码不依赖任何数据库库。

## 当前进度：六阶段全部完成

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 存储层：页式文件 / BufferPool / 定长+变长表 / REPL | ✅ 完成 |
| 2 | B+ 树索引：分裂 / 借位合并 / 范围扫描 / 统计接口 | ✅ 完成 |
| 3 | SQL 解析器 + 火山模型执行引擎 + 代价优化器 | ✅ 完成 |
| 4 | 200 条端到端 SQL 测试 + 100 万行压测 + JMH（见 [PERF.md](PERF.md)） | ✅ 完成 |
| 5 | WAL + 2PL + 死锁检测 + 崩溃恢复 | ✅ 完成 |
| 6 | JDBC 驱动 + 纯 JDBC Demo | ✅ 完成 |
| 增强 | Hash Join / 变长键 B+ 树（VARCHAR 索引）/ steal+no-force 恢复 | ✅ 完成 |
| **MiniDB Studio** | **本地 Web 工作台：可视化管理 + SQL 开发 + 内核观察 + 教学实验** | ✅ 完成 |

**369 项测试全绿**（内核 350 + Studio Web 层 19）。设计文档见 [docs/](docs/)（每层一篇 + 踩坑总结 + [Studio 文档](docs/studio/)）。

## MiniDB Studio（Web 工作台）

双击 **启动 MiniDB Studio.bat**（或先运行 build-studio.bat 构建）→ 浏览器自动打开
<http://127.0.0.1:8080>（仅本机监听）：

- **开始页**：创建/打开数据库、最近列表、示例数据库（school.db）、新用户引导
- **数据浏览**：表列表 + 数据网格（内核侧分页/排序/筛选），新增/编辑/删除按 RID 精确定位
- **SQL 工作台**：CodeMirror 编辑器（高亮/补全/行号），单语句/选中/脚本执行（真词法拆分），
  结果/消息/执行计划/历史，BEGIN·COMMIT·ROLLBACK 与按钮同源
- **索引与计划**：索引 CRUD、真实 B+ 树结构快照（SVG）、优化器执行计划树
- **内核实验室**（全部独立实验库、全部真实内核行为）：
  存储与缓冲池（4KB 页/Slot/RID、读命中→变脏→写回）· SQL 执行流程（Token→AST→Plan→Result）·
  事务与锁（双会话、RC/RR/锁等待/死锁模板、锁等待图）· WAL 与恢复（真实 crash → redo/undo 报告 →
  一致性检查）· 性能实验（实时测量 SeqScan vs IndexScan）· JDBC（标准驱动全流程）
- **学习与帮助**：架构速览、SQL 方言速查、文档索引

细节见 [docs/studio/](docs/studio/)（架构 / API / 会话模型 / 实验设计 / 前端设计 / 验收清单）与
[MiniDB-Web-Integration-Notes.md](MiniDB-Web-Integration-Notes.md)（内核集成摸底）。

```bash
# 构建与启动
build-studio.bat          # 前端 build → resources → fat jar
启动 MiniDB Studio.bat    # 检查 Java/端口 → 启动 → 验证健康接口 → 开浏览器

# 开发模式（前端热更新）
cd frontend && npm install && npm run dev   # Vite :5173，/api 代理 :8080
java -cp "target/classes;<jackson 等>" minidb.web.MiniDbWebServer --port 8080
```

启动脚本会先拒绝已被占用的 8080 端口，并在启动后轮询
`GET /api/v1/health`（默认最多 30 秒）。只有响应同时满足
`success=true`、`data.status=ok`、`data.service=minidb-studio` 时才会显示就绪并打开浏览器。
验证正常启动、端口占用和服务启动失败的方法见
[启动流程验证](docs/studio/startup-verification.md)。

## 快速开始

```bash
mvn test                                   # 全量测试（350 项）

# SQL REPL（阶段 3 起支持完整 SQL 子集）
java -cp target/classes minidb.repl.Repl

# 阶段 6 Demo：纯 JDBC 代码连 MiniDB 跑 CRUD + 事务
java -cp target/classes minidb.demo.JdbcDemo [jdbc:minidb:路径]
```

REPL 同时保留阶段 1 的存储层白盒命令（`.open/.create/.insert/.scan/.get/.delete/.update/.count/.pages/.stats`）。

## 支持的 SQL 子集

```sql
CREATE TABLE [IF NOT EXISTS] t (id INT PRIMARY KEY, name VARCHAR(20), score DOUBLE);
CREATE INDEX idx ON t(id);                       -- INT/BIGINT/VARCHAR(≤255) 单列唯一索引
CREATE INDEX idx2 ON t(name);                    -- VARCHAR 索引：等值/范围/前缀走索引
INSERT INTO t VALUES (1,'a',1.5), (2,'b',2.5);    -- 支持列清单与多行
UPDATE t SET score = score + 1 WHERE id = 1;
DELETE FROM t WHERE name LIKE 'a%';

SELECT [DISTINCT] a, b AS x, COUNT(*), SUM(c), AVG(c), MIN(c), MAX(c)
FROM t1 [INNER|LEFT [OUTER]] JOIN t2 ON t1.id = t2.id, t3
WHERE a > 1 AND b IN (SELECT x FROM t3) OR EXISTS (SELECT 1 FROM t3)
   OR c BETWEEN 1 AND 10 AND d IS [NOT] NULL AND e NOT LIKE '_x%'
GROUP BY a, b HAVING COUNT(*) > 1
ORDER BY a DESC, COUNT(*) LIMIT 10 OFFSET 5;
SELECT 1 + 1;                                     -- 无 FROM 常量表达式
BEGIN; ... COMMIT; / ROLLBACK;                    -- 显式事务
```

执行计划：`minidb.exec.Executor.explain(sql)`（优化器测试直接对 EXPLAIN 断言）。

## 各层一览（详见 docs/）

| 层 | 要点 | 文档 |
|----|------|------|
| 存储 | 4KB 页 + 空闲位图；BufferPool LRU/脏页/pin；定长槽位表 + 变长 slotted page；Catalog 目录链 | [01](docs/01-存储层设计.md) |
| B+ 树 | **变长键双端布局**（键 ≤255B，INT 编码 8B/VARCHAR 直存 UTF-8）；按需预分裂 + 字节均衡；开闭边界范围扫描；long/String 双门面 10 万键对拍 TreeMap | [02](docs/02-B+树索引设计.md) |
| SQL 解析 | 手写 Lexer（77 词元）+ 递归下降 Parser；record + sealed AST；fail-fast 错误带位置 | [03](docs/03-SQL解析器设计.md) |
| 执行引擎 | 火山模型 12 算子（含 **HashJoinExec**：等值 ON 自动选用，2万×5千等值连接 ≈1900×）；常量折叠；计划期表达式编译；哈希聚合 + AST 改写；极简代价优化器（索引选择 + 贪心 JOIN 序 + 谓词下推） | [04](docs/04-执行引擎与优化器设计.md) |
| 事务恢复 | WAL + 严格 2PL + 等待图死锁检测；**steal + no-force**（日志临界区 + FlushHook/syncUpTo + pageLsn）；恢复 = redo + undo 两遍条件重放；随机截断断电模拟；20 线程 10 万次转账守恒（no-force 后 9.9s） | [05](docs/05-事务与崩溃恢复设计.md) |
| JDBC | 标准驱动 ServiceLoader 注册；Statement/PreparedStatement/ResultSet/MetaData；参数文本化绑定 + 字符串转义；35 项测试 | [06](docs/06-JDBC驱动设计.md) |
| 复盘 | 19 个真实踩坑记录（含 WAL 读不出的重大发现、变长键布局三轮调试） | [07](docs/07-从零写数据库：我踩过的坑.md) |

## 性能摘录（详见 [PERF.md](PERF.md)）

100 万行、缓冲池 16 MB、单线程：

| 项目 | 结果 |
|------|------|
| 插入 100 万行（Table API） | 514 ms（194 万行/秒） |
| 点查 走索引 vs 全表扫描 | 0.055 ms vs 45 ms（**≈818×**） |
| 范围查 1000 行 走索引 vs 扫描 | 0.222 ms vs 42 ms（≈189×） |
| JMH SQL 点查端到端 | 1.551 µs/op |
| Hash Join 2万×5千等值连接 | 13 ms（纯 NLJ 同规模 25.2 s，≈1900×） |
| 小事务提交（2 UPDATE + commit，fsync 关） | ≈8,700 事务/秒 |
| 转账压测 20 线程×10 万次（守恒） | 9.9 s（force 协议时代 ≈110 s） |

## 阶段 1：存储层

- **Page**：固定 4KB，带 dirty/pinCount/pageLsn（pageLsn 供阶段 5 WAL 使用）。
- **DiskManager**：页粒度随机读写，文件即页的线性序列。
- **StorageEngine**：自管理页分配与回收。页 0 元数据 + 页 1 目录 + 链式**空闲空间位图页**（每页覆盖 32736 个页号，自举分配）。回收页磁盘清零，防止旧数据泄漏。
- **BufferPool**：LRU 淘汰（LinkedHashMap accessOrder）+ 脏页写回 + **引用计数**（pin>0 不可淘汰；全部 pin 时抛 BUFFER_FULL，作为故障注入点）。带命中/淘汰/写回统计与 FlushHook（阶段 5 的"先写日志"挂点）。
- **Table 抽象**：
  - `FixedTable` 定长记录：定长槽 + 页尾占用位图，原地更新，**RID 永不变化**。
  - `VarTable` 变长记录（VARCHAR）：slotted page（槽位数组向上、记录向下），空槽空闲链 + 空洞统计，放不下时页内**压缩**（槽号不变），UPDATE 放不下原页时迁移并返回新 RID。
  - 同表数据页用双向链表串联，全空页自动归还存储引擎。
- **Catalog**：目录页链持久化表 schema / firstPage / 索引元数据，支持多页溢出。
- **验证**：定长/变长表各插入 **10 万条记录**再全部读回，逐字段比对一致（`Phase1LargeDataTest`）。

## 阶段 2：B+ 树索引

- **BPlusTree**（`minidb.btree`）：key = long，value = 行 RID。
  - 节点即 4KB 页（内部节点 fanout 338 / 叶 255），**全部读写通过 BufferPool**，绝不直接操作文件。
  - 插入：节点满即分裂（叶 push-right 保留分隔键在右节点；内部节点中间键上推），根分裂增高。
  - 删除：低于最小键数先向左右兄弟**借位**（旋转，父分隔键同步更新），借不到则**合并**（分隔键下坠），父递归处理，空内部根自动降级。
  - 叶节点双向链表 → 等值查找 / 有序全扫描 / **开闭边界可选的范围扫描**。
  - 统计接口：树高、内部/叶节点数、叶平均利用率、总键数；另有 `validate()` 结构不变量校验（键有序、子树边界、叶链双向一致）。
- **Database.createIndex / dropIndex**：对现有数据全表扫描建唯一索引（INT/BIGINT 列），索引根页登记进 Catalog 持久化；dropIndex/dropTable 释放整棵树节点页。
- **验证**：**随机插入 10 万 key 与 java.util.TreeMap 逐条比对**（等值查找、全序扫描、20 组随机范围扫描与 subMap 完全一致）；**随机删除 5 万 key 后再比对一次**；重开文件持久化验证；8 页小缓冲池下 3000 键压力正确性；坏页类型故障注入。

## 测试

```bash
mvn test    # 369 项全绿（内核 350 + Studio Web 层 19）
```

覆盖：字节编解码边界、磁盘 IO 故障、缓冲池 LRU/pin/脏页/日志临界区、位图链跨页、定长/变长表边界（VARCHAR 超长、空洞压缩、页回收）、目录溢出、B+ 树 long/String 双门面各 10 万键对拍 TreeMap（含删 5 万再对拍、CJK/前缀键）、词法/语法 40 项结构断言、执行器 60 项（含 Hash Join 语义 8 项）+ 200 条端到端 SQL 脚本、锁/隔离/恢复/WAL 36 项（含 steal 淘汰、no-force redo、迁移 UPDATE 双向、WAL 往返与残缺尾自愈、随机截断断电、20 线程转账守恒）、JDBC 35 项。

## 文件布局

```
src/main/java/minidb/
├── common/    Rid、Bytes(小端编解码)、统一异常
├── storage/   Page/DiskManager/StorageEngine/BufferPool + Table 抽象 + Catalog/Database
├── btree/     BPlusTree/BTreeNode（阶段 2）
├── sql/       Lexer/Parser/Token/Ast（阶段 3）
├── exec/      火山算子 + 表达式编译 + Executor(含优化器)（阶段 3，阶段 5 并发化）
├── txn/       LockManager/TxnSession（阶段 5）
├── wal/       WalLog/Recovery（阶段 5）
├── jdbc/      标准驱动 8 类（阶段 6）
├── demo/      JdbcDemo（阶段 6）
├── repl/      REPL（SQL 模式 + 阶段 1 白盒命令）
├── perf/      Stress1M 压测程序（阶段 4）
└── web/       MiniDB Studio Web 层（服务器/路由/会话/服务/实验室）

frontend/                  Studio 前端（React + TS + Vite + CodeMirror）
src/main/resources/webroot/ 前端构建产物（Java 服务器直接提供静态页）
docs/studio/               Studio 架构/API/会话模型/实验设计/前端设计/验收清单
```
