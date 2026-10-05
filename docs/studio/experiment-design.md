# 内核实验室实验设计

统一结构（计划书 §20）：实验目标 → 实验环境 → 实验步骤 → 执行内核状态变化 →
实验结果 → 原理解释。所有数据来自真实内核，工作库与实验库物理隔离（`*.exp.db`，
存于 `--data-dir/.minidb-lab/`）。

## 1. 存储与缓冲池（/lab/storage）

- **目标**：观察 4KB 页 / Slot / RID / 定长 vs 变长记录；缓冲池读命中 → 页变脏 → 写回。
- **环境**：`storage.exp.db`（fixed_demo 定长 + var_demo 变长）、`bufferpool.exp.db`
  （50 行 + 索引，持久实例保证计数连续）。
- **数据来源**：页视图经 `BufferPool.withPage` 读真实页头（TablePageHeader）；
  缓冲池计数来自 BufferPool 计数器；页状态来自 `snapshot()`。
- **动作**：插入定长/变长记录后页面使用空间实时变化；全表扫描观察 miss/淘汰；
  索引点查只加载树路径；UPDATE 让命中页 dirty；flush 按 WAL 先写原则落盘（writebacks↑）。

## 2. SQL 执行流程（/lab/pipeline）

- **目标**：一条 SELECT 的完整管线 SQL → Token → AST → Plan → Executor → Result。
- **数据来源**：`Lexer.tokenize()`（真词法）、`Parser.parse()`（真实 AST，AstJson 序列化）、
  `Executor.explain()`（真实优化器）、`runSelect`（真实执行）。

## 3. 事务与锁（/lab/txn）

- **目标**：READ COMMITTED / REPEATABLE READ 语义、锁等待、死锁检测。
- **环境**：`txn.exp.db` account 表 50 行 + id 唯一索引（行级锁实验要求点查走
  IndexScan，只锁命中行——全表扫描会瞬时锁全表行）。
- **机制**：Session A/B 是两个真实 TxnSession；可能阻塞的 SQL 步骤在后台线程真实执行，
  300ms 未完成转 pending（前端轮询 /txn/poll 与 /txn/state 的锁等待图）。
- **死锁模板**：RR 读各自持有两行 S 锁 → 交叉 UPDATE 形成循环等待 → 内核等待图
  DFS 检测，后请求者为牺牲者（DEADLOCK 错误）→ 回滚牺牲者释放锁。
  注：UPDATE 扫描路径会对经过的行加瞬时 X 锁，"双方先 UPDATE"不构成死锁；
  必须"先 RR SELECT 持锁，再交叉 UPDATE"。
- **实时数据视图**：`runSelect` 无锁读（session=null），如实呈现未提交修改。

## 4. WAL 与恢复（/lab/recovery）

- **目标**：真实断电 → 真实 redo+undo 恢复 → 一致性检查。
- **流程**：`recovery.exp.db` 建 account → T1（INSERT×2+COMMIT，WAL fsync）→
  T2（INSERT+UPDATE，不提交）→ 读 WAL 原文（readAll）→ `db.crash()`
  （缓冲池不刷盘、日志不 fsync 不截断——**不是** close 冒充）→ 重新 `Database.open`
  （构造器自动执行真实 Recovery）→ 展示 `RecoveryReport`（ANALYZE/REDO/UNDO 每步
  是否应用与原因）→ 一致性检查（提交数据在、未提交修改被撤销）。
- **注意**：crash 后注册表条目 discard（句柄作废），实验可重复运行。

## 5. 性能实验（/lab/perf）

- **目标**：同表同 SQL 对比无索引 SeqScan vs B+ 树 IndexScan。
- **流程**：`perf.exp.db` 单事务灌 N 行（默认 10000）→ 无索引预热+计时 →
  CREATE INDEX → 再计时 → 展示两份真实 explain 文本（建索引前 SeqScan、后 IndexScan）。
- **诚实标注**：结果页明确显示 数据规模/查询次数/预热/运行时间，并注明
  PERF.md 为历史测试结果，不能与本次实时测量混淆。

## 6. JDBC 实验（/lab/jdbc）

- **目标**：标准 JDBC（Connection/Statement/PreparedStatement/ResultSet/事务）操作真实 MiniDB。
- **环境**：`jdbc.exp.db`，由 MiniDbDriver 自己打开（不经 DatabaseRegistry，
  保持同路径单实例约束）。
- **演示**：建表 → PreparedStatement 批量插入（中文）→ ResultSet 遍历 →
  setAutoCommit(false)+UPDATE+rollback（撤销）→ 提交 → DELETE → 最终数据表。
  每步附代码样例与真实执行日志。
