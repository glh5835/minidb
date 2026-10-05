# MiniDB Studio HTTP API

统一前缀 `/api/v1`。除 `/health`、`POST /sessions`、`/labs/*` 外都需要会话标识，
通过 `X-MiniDB-Session` 头、`minidb-session` Cookie 或 `?sessionId=` 查询参数任一通道携带
（三通道兼容：部分内嵌浏览器/代理会剥离自定义头）。

## 统一响应协议

成功：`{"success": true, "data": ..., "meta"?: {...}}`
失败：`{"success": false, "error": {"code", "message", "line"?, "column"?}}`
绝不向前端暴露 Java StackTrace；HTTP 状态码 200/400/404/405/409/410/500。

## 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /sessions | 创建会话，响应 Set-Cookie |
| GET | /session | 会话状态（绑定数据库/事务/表列表） |
| DELETE | /session | 显式关闭（回滚活动事务） |

空闲 15 分钟自动回收：回滚未提交事务 → 释放数据库引用。之后请求返回
`SESSION_EXPIRED`（HTTP 410），前端提示"原会话已结束，未提交事务已经回滚"。

## 数据库

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /databases/create | `{path}` 创建并绑定（相对路径基于 --data-dir） |
| POST | /databases/open | `{path}` 打开并绑定 |
| POST | /databases/close | 关闭并解绑 |
| POST | /databases/check | `{path}` 文件是否存在/是否已打开（最近列表用） |
| POST | /databases/sample | 创建示例库 school.db（student/course/score + 3 索引） |

## 表与数据（RID 契约）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /tables | 表列表（行数/页数/索引摘要） |
| POST | /tables | `{name, columns:[{name,type,size}]}` 建表（真实 CREATE TABLE 路径） |
| GET | /tables/{name}/schema | 列定义 |
| GET | /tables/{name}/rows?offset&limit&sort&dir&filterColumn&filterOp&filterValue | 内核侧分页浏览，行带 `_rid`（"page/slot"），默认 50 行/页，上限 500 |
| POST | /tables/{name}/rows | 行对象插入 → `{rid}` |
| PUT | /tables/{name}/rows/{page}/{slot} | RID 精确更新 → `{oldRid, newRid, migrated}`（变长记录迁移时前端据此刷新） |
| DELETE | /tables/{name}/rows/{page}/{slot} | RID 精确删除 |
| DELETE | /tables/{name} | 删表 |

RID 失效（记录已迁移/被删）返回 `RID_STALE`，文案"该记录已发生变化，请刷新后重试"。

## SQL

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /sql/execute | `{sql, onlyIndex?}` 脚本执行。真词法拆分（Lexer SEMI，字符串内 `;` 安全）；
遇错停止并带 语句序号/行列/错误码。SELECT 内核入口截断 1000 行（`truncated` + `notice`）。
SQL 文本的 BEGIN/COMMIT/ROLLBACK 与事务按钮共用会话事务。显式事务中 DDL 返回 `TRANSACTION_ACTIVE` |
| POST | /sql/plan | `{sql}` 真实优化器计划（text + tree） |
| POST | /sql/pipeline | `{sql}` Token → AST → 计划 → 结果（SQL 执行流程实验） |

## 事务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /transactions | 当前事务状态（autoCommit/isolation/txnId/heldLocks） |
| POST | /transactions/begin | `{isolation: READ_COMMITTED|REPEATABLE_READ}` |
| POST | /transactions/commit / rollback | 无活动事务 → `TRANSACTION_REQUIRED`；已活动 → `TRANSACTION_ACTIVE` |

## 索引

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /indexes | 全部索引（含 stats：树高/节点/利用率/键数） |
| POST | /indexes | `{name, table, column}`（唯一索引；重复数据建索引失败） |
| DELETE | /indexes/{name} | 删除 |
| GET | /indexes/{name}/btree | 只读 B+ 树快照（root/height/nodes[{pageId,kind,keys,children,next,prev,rids,usedBytes}]） |

## 快照

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /snapshots/buffer-pool | 容量/命中/缺失/淘汰/写回 + 每页 pin/dirty/pageLsn/类型 |
| GET | /snapshots/locks | 全部锁条目（持锁者/模式/等待队列） |
| GET | /snapshots/pages?table= | 表页链视图（槽位数/空闲/链指针/PageLSN） |
| GET | /snapshots/wal | WAL 原文解码（readAll） |
| POST | /snapshots/flush | 刷盘 |

## 实验室（独立实验库 .minidb-lab/*.exp.db，免会话）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /labs/txn/setup | 重置 account 实验库（50 行 + id 唯一索引） |
| POST | /labs/txn/exec | `{session: A|B, action: BEGIN|SQL|COMMIT|ROLLBACK, sql?, isolation?}`；
锁阻塞时 300ms 转后台挂起返回 `pending:true`（语句在真实线程执行） |
| GET | /labs/txn/poll | 轮询挂起操作结果 |
| GET | /labs/txn/state | 两会话状态 + 真实锁快照 + 实时数据（无锁读） |
| POST | /labs/recovery/run | 全自动：建库 → T1 提交/T2 不提交 → 读 WAL → db.crash() → 重开触发真实 Recovery → 报告 + 一致性检查 |
| POST | /labs/storage/setup · GET state · POST act | 定长/变长表页观察；插入/删除后页面变化 |
| POST | /labs/bp/setup · GET state · POST act | 缓冲池读命中/变脏/flush 写回（计数跨请求连续） |
| POST | /labs/perf/run | `{rows, queries, warmup}` 实时测量 SeqScan vs IndexScan + 两份真实计划 |
| POST | /labs/jdbc/run | 真实 MiniDbDriver 跑标准 JDBC 流程（独立实验库，不经注册表） |

## 错误码

`DATABASE_NOT_FOUND / DATABASE_ALREADY_EXISTS / DATABASE_NOT_OPEN / TABLE_NOT_FOUND /
INDEX_NOT_FOUND / SQL_PARSE_ERROR / SQL_EXECUTION_ERROR / TRANSACTION_REQUIRED /
TRANSACTION_ACTIVE / UNIQUE_CONSTRAINT_VIOLATION / RID_STALE / SESSION_EXPIRED /
RESULT_TRUNCATED(通知) / EXPERIMENT_FAILED / EXPERIMENT_NOT_READY / VALIDATION_ERROR /
DISCONNECTED(前端) / DEADLOCK / LOCK_ERROR / BUFFER_FULL / IO_ERROR …`

完整映射见 `MiniDB-Web-Integration-Notes.md` §6。
