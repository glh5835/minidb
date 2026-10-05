# Studio 会话模型

## 三个层次

```
DatabaseRegistry（全 JVM）
  path(绝对, normalize) → Database 单例 + 引用计数
  ├─ 保证"同一路径只打开一个 Database 实例"（避免多实例写同一文件）
  ├─ 引用计数归零 → 物理 close（checkpoint：flush + WAL truncate）
  ├─ crash(entry)    恢复实验用：abruptClose，注册表移除
  └─ discardEntry    崩溃后句柄作废时移除（不再触发 close）

SessionManager
  sessionId → WebSession（空闲 15 分钟回收线程）
  ├─ bindDatabase(path)：经 registry.acquire 单例打开，Executor 绑定
  ├─ require(sid)：过期抛 SESSION_EXPIRED(410)
  └─ reapIdle()：回滚活动事务 → 关会话 → release 数据库引用

WebSession（浏览器工作会话）
  ├─ executor   独立 Executor（每个浏览器会话一套 SQL 上下文）
  ├─ txn        当前显式 TxnSession（SQL 文本 BEGIN/COMMIT/ROLLBACK 与
  │             事务按钮共用同一状态机 —— 计划书 §13）
  ├─ defaultIsolation  默认 REPEATABLE_READ
  └─ close()    回滚活动事务、清空绑定
```

## 会话标识传输

`X-MiniDB-Session` 头 → `minidb-session` Cookie → `?sessionId=` 查询参数，
服务器三通道依次读取。创建会话时同时下发 Set-Cookie；前端 localStorage 与 Cookie 双写。
原因：部分内嵌浏览器/网络代理会剥离自定义请求头。

## 事务边界

- SQL 文本里的 BEGIN/COMMIT/ROLLBACK 由 Web 层拦截翻译到 `WebSession.txn`，
  与顶部事务按钮完全同源；Executor.execute(String) 的内部 REPL 状态机不被使用。
- autoCommit 路径：显式事务外的一切 DML/SELECT 以语句级事务执行（内核语义）。
- 显式事务期间 DDL 被 Web 层拒绝（`TRANSACTION_ACTIVE`）：DDL 不走事务日志、无法回滚。
- JDBC 实验是唯一例外：MiniDbDriver 自行打开 Database 实例，因此 JDBC 实验文件
  绝不进入 DatabaseRegistry（保证同路径单实例约束不被破坏）。

## 生命周期场景

| 场景 | 行为 |
|---|---|
| 浏览器刷新 | 前端持 sessionId 重新查询 /session；会话仍在 → 恢复 UI；过期 → 自动新建并提示"原会话已结束，未提交事务已经回滚" |
| 浏览器关闭/崩溃 | 无请求 → 15 分钟后 reapIdle 回滚事务、释放数据库 |
| 服务器关闭 | registry.close() 顺序 checkpoint 所有打开的数据库 |
| 空闲超时后再操作 | SESSION_EXPIRED，前端自动重建会话 |
