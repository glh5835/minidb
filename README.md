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

**331 项测试全绿**。设计文档见 [docs/](docs/)（每层一篇 + 踩坑总结）。

## 快速开始

```bash
mvn test                                   # 全量测试（331 项）

# SQL REPL（阶段 3 起支持完整 SQL 子集）
java -cp target/classes minidb.repl.Repl

# 阶段 6 Demo：纯 JDBC 代码连 MiniDB 跑 CRUD + 事务
java -cp target/classes minidb.demo.JdbcDemo [jdbc:minidb:路径]
```

REPL 同时保留阶段 1 的存储层白盒命令（`.open/.create/.insert/.scan/.get/.delete/.update/.count/.pages/.stats`）。

## 支持的 SQL 子集

```sql
CREATE TABLE [IF NOT EXISTS] t (id INT PRIMARY KEY, name VARCHAR(20), score DOUBLE);
CREATE INDEX idx ON t(id);                       DROP INDEX idx;  DROP TABLE t;
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
| B+ 树 | 节点即页（叶 255 / 内部 338 fanout），经缓冲池读写；分裂/借位/合并；开闭边界范围扫描；10 万 key 对拍 TreeMap | [02](docs/02-B+树索引设计.md) |
| SQL 解析 | 手写 Lexer（77 词元）+ 递归下降 Parser；record + sealed AST；fail-fast 错误带位置 | [03](docs/03-SQL解析器设计.md) |
| 执行引擎 | 火山模型 11 算子；计划期表达式编译；哈希聚合 + AST 改写；子查询物化/重放双路径；极简代价优化器（索引选择 + 贪心 JOIN 序 + 谓词下推） | [04](docs/04-执行引擎与优化器设计.md) |
| 事务恢复 | WAL（逻辑日志）+ 严格 2PL + 等待图死锁检测；no-steal/force-at-commit → 恢复=一遍扫描+条件 undo；随机截断断电模拟；20 线程 10 万次转账守恒 | [05](docs/05-事务与崩溃恢复设计.md) |
| JDBC | 标准驱动 ServiceLoader 注册；Statement/PreparedStatement/ResultSet/MetaData；参数文本化绑定 + 字符串转义；35 项测试 | [06](docs/06-JDBC驱动设计.md) |
| 复盘 | 16 个真实踩坑记录（含 WAL 读不出的重大发现） | [07](docs/07-从零写数据库：我踩过的坑.md) |

## 性能摘录（详见 [PERF.md](PERF.md)）

100 万行、缓冲池 16 MB、单线程：

| 项目 | 结果 |
|------|------|
| 插入 100 万行（Table API） | 514 ms（194 万行/秒） |
| 点查 走索引 vs 全表扫描 | 0.055 ms vs 45 ms（**≈818×**） |
| 范围查 1000 行 走索引 vs 扫描 | 0.222 ms vs 42 ms（≈189×） |
| JMH SQL 点查端到端 | 1.551 µs/op |

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
mvn test    # 331 项全绿
```

覆盖：字节编解码边界、磁盘 IO 故障、缓冲池 LRU/pin/脏页、位图链跨页、定长/变长表边界（VARCHAR 超长、空洞压缩、页回收）、目录溢出、B+ 树对 TreeMap 10 万键对拍（含删 5 万再对拍）、词法/语法 40 项结构断言、执行器 52 项 + 200 条端到端 SQL 脚本、锁/隔离/恢复/WAL 33 项（含 WAL 记录往返与残缺尾部自愈、DDL 刷盘后未提交 DML 的恢复撤销、随机截断断电模拟、20 线程 10 万次转账总额守恒）、JDBC 35 项。

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
└── perf/      Stress1M 压测程序（阶段 4）
```
