# Studio 验收清单（Definition of Done）

每阶段统一标准（计划书 §49）：UI 完成 · API 完成 · 真实内核连接 · 异常状态 ·
自动测试 · 浏览器验证 · 原有测试无退化 · 文档更新。

## 自动测试

- [x] 内核既有测试全绿：350 项（含唯一索引强制、快照、恢复报告等增量）
- [x] Web Service 测试 19 项（WebServiceTest）：
  - 数据库：创建/重复创建报错/打开不存在报错/关闭重开/未绑库报 DATABASE_NOT_OPEN
  - RID 契约：_rid 形如 page/slot、BIGINT JSON 字符串化、中文往返、分页/排序/筛选、
    变长更新迁移 oldRid/newRid、迁移后旧 RID 报 RID_STALE、删除后同 RID 报 RID_STALE
  - SQL：SELECT 列序、脚本真词法拆分（字符串内 `;` 不切分）、解析错误带行列与
    SQL_PARSE_ERROR、1000 行内核截断 + notice、唯一索引冲突 UNIQUE_CONSTRAINT_VIOLATION
    且 autoCommit 整体回滚
  - 事务：SQL 的 BEGIN 与按钮共用（按钮期 begin 报 TRANSACTION_ACTIVE）、
    回滚不可见/提交持久、无事务 COMMIT 报 TRANSACTION_REQUIRED、显式事务期 DDL 拒绝
  - 索引与计划：CRUD、B+ 树快照、点查走 IndexScan（真实优化器）
  - 快照契约：缓冲池/锁/页视图/WAL
  - 会话过期：空闲回收 → SESSION_EXPIRED、无残留锁
  - 实验室：恢复报告含 REDO/UNDO 且一致性检查全过、锁等待图、死锁被真实检测器捕获、
    存储/缓冲池（脏页→写回）、性能（SeqScan/IndexScan 计划文本）、JDBC（rollback 日志 + 2 行）

## 浏览器关键流程（§46 第四层，IAB 实测通过）

- [x] 创建数据库 school.db → 顶栏显示库与"工作库"徽章
- [x] SQL 工作台：CREATE TABLE / 批量 INSERT（中文 + BIGINT 超 JS 精度）/ SELECT 过滤排序
      → 结果网格正确（3 条语句，历史可回填）
- [x] 数据浏览：网格 + RID 列 + 分页；添加记录（BIGINT 字符串）；编辑记录（toast 确认）；
      删除记录（二次确认"删除记录"按钮）→ 行数 4→3
- [x] 索引与计划：索引列表 2 行完整按钮；B+ 树 SVG（真实页 4、6 键）；点查计划 = IndexScan
- [x] 事务闭环：BEGIN → 顶栏 ACTIVE·REPEATABLE_READ → UPDATE → 事务内查得新值 →
      ROLLBACK → 数据恢复 88.5 → 顶栏自动提交
- [x] 刷新恢复：reload 后会话/绑定/数据完好
- [x] 实验室 6 页全部真实运行：恢复实验（WAL 记录 → ANALYZE/REDO/UNDO 报告 → 一致性检查全过）、
      事务锁（RC/RR/锁等待/死锁模板一键运行）、存储/缓冲池（初始化/插行/扫描/flush）、
      SQL 管线（token/AST/计划/结果）、性能（实测对比）、JDBC（日志 + 最终数据）

## 视觉验收（visual-judge 两轮）

- [x] start / db / sql / storage / txn / pipeline / recovery / index 全部 pass
- [x] 修复过程中澄清的假阳性：截图时序错位（后台标签绘制节流）、索引卡片宽度
      （380→470px 后操作列完整）

## 边界必测（§47）覆盖情况

| 边界 | 覆盖 |
|---|---|
| 中文 / BIGINT MAX / VARCHAR 边界 / 重复行 / 唯一冲突 | WebServiceTest + 浏览器实测 |
| VARCHAR 迁移 / RID 更新 / 重开持久化 | updateByRidWithMigrationAndStale + DDL 落盘跨强杀验证 |
| 字符串中的 `;` / 错误 SQL / 1000 行截断 | sqlScriptSplit… / resultTruncationAt1000 |
| BEGIN/COMMIT/ROLLBACK / 锁等待 / 死锁 / Session 超时 | transactionFlow… / txnLabLockWaitAndDeadlock / sessionExpiryReapsTxn |
| Crash / Redo / Undo | recoveryLabRealReport（真实 crash 语义） |

## 兼容性红线

- [x] 369 项测试全绿（内核 350 + Web 19），无退化
- [x] REPL / JDBC / 数据文件格式 / 既有示例未动（DDL 落盘补齐为既有设计意图的实现，
      行为修复：崩溃后索引不再丢失）
- [x] Web 只做增量适配；无任何伪造内核结果（计划树/快照/报告/耗时全部真实）
