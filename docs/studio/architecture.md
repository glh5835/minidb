# MiniDB Studio 架构

## 总体分层

```
浏览器（React + TS + Vite，frontend/）
  │  /api/v1/*  JSON（会话经 头/Cookie/查询参数 三通道）
  ▼
minidb.web（JDK 17 HttpServer + Jackson）
  MiniDbWebServer ── HTTP 路由 / 静态资源 / 统一响应包装
    ├─ Router        method + 路径模板 → Handler
    ├─ SessionManager WebSession（独立 Executor / TxnSession / 隔离级别）
    ├─ DatabaseRegistry 路径 → Database 单例 + 引用计数
    ├─ MiniDbService 数据库/表/行/SQL/事务/索引/快照
    ├─ LabService    内核实验室（独立实验库 *.exp.db）
    └─ Json/AstJson  BIGINT 字符串化、AST 序列化
  │  只调用真实内核公开 API
  ▼
MiniDB 内核（storage / btree / sql / exec / txn / wal / jdbc）—— 未改动架构
```

## 核心原则落地

- **内核优先**：Web 层没有任何 SQL 解释/数据修改逻辑；RID 编辑复用 Executor 的
  WAL/行锁/undo/索引维护机制（`insertRow`/`updateRowByRid`/`deleteRowByRid` 内部走
  `insertWithLog`/`applyRowUpdate`/`applyRowDelete`）。
- **兼容性**：REPL/JDBC/文件格式/既有测试全部不动；369 项测试全绿。
- **真实结果**：计划树来自 `Executor.explain()`（真实优化器）；B+ 树来自
  `BPlusTree.snapshot()`（真实页数据）；缓冲池/锁快照来自内核只读接口；
  恢复报告来自 `Recovery` 真实执行路径。

## 内核增量修改（全部增量式，不破坏兼容）

| 修改 | 位置 | 说明 |
|---|---|---|
| 唯一索引 DML 强制 | exec/Executor | `maintainIndexesOnInsert`/UPDATE 索引维护检查 `IndexKeys.insert` 返回值，重复抛 `UNIQUE` |
| 锁快照 | txn/LockManager | `snapshot()` 只读（持锁者/等待队列） |
| 缓冲池快照 | storage/BufferPool | `snapshot()` 只读（pin/dirty/pageLsn/类型）；`Page.typeOrNull()` 容错读系统页 |
| 恢复报告 | wal/Recovery | `RecoveryReport` 收集 ANALYZE/REDO/UNDO 决策；`Database.recoveryReport()` 暴露本次打开的恢复结果 |
| B+ 树快照 | btree/BPlusTree | `snapshot(keyType)` 层序节点 DTO（keys/children/叶链/使用率） |
| RID 浏览/编辑 | exec/Executor | `browse()`（内核分页/排序）、`getRowByRid/insertRow/updateRowByRid/deleteRowByRid` |
| 结果截断 | exec/Executor | `runSelect(stmt, session, maxRows)` 计划外套 Limit，内核入口限行 |
| DDL 落盘 | storage/Database | createIndex/dropIndex/dropTable 补 `engine.flush()`（与 createTable 的“DDL 直接落盘”一致） |

## 会话与并发

见 [session-model.md](session-model.md)。

## 目录

```
frontend/                 React 前端（构建产物 dist → src/main/resources/webroot）
src/main/java/minidb/web/ Web 层（服务器/路由/服务/实验室）
src/main/resources/webroot/ 前端静态资源（由 Java 服务器直接提供）
build-studio.bat          构建：前端 build → resources → shade fat jar
启动 MiniDB Studio.bat    一键启动：检查 Java/端口 → 启动 → 验证健康接口 → 开浏览器
```
