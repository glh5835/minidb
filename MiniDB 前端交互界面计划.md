undefined undefined

## 1. 项目目标

在现有 MiniDB Java 项目基础上新增一个 **MiniDB Studio 本地网页工作台**，直接连接真实 Java 数据库内核，使现有数据库能力从“命令行 / JDBC 可用”升级为：

**可视化操作 + SQL 开发 + 内核观察 + 数据库教学实验 + 调试验证**

工作台主要解决两个场景：

1. **日常数据库操作**

   - 创建、打开和管理本地数据库

   - 创建表、浏览表结构

   - 查看、增加、修改、删除数据

   - 编写并执行 SQL

   - 创建和查看索引

   - 控制事务

   - 查看执行计划

2. **MiniDB 教学与内核实验**

   - 观察页、记录和缓冲池

   - 观察 B+ 树

   - 观察 SQL 解析和执行流程

   - 双会话事务实验

   - 锁等待和死锁实验

   - WAL 与恢复实验

   - 索引性能对比

   - JDBC 使用演示

MiniDB Studio 必须操作 **真实 MiniDB 内核**，不得另外实现一套模拟数据库逻辑。

---

# 2. 核心原则

整个项目开发过程中遵守以下原则。

### 2.1 内核优先

前端只是 MiniDB 的可视化入口。

不得为了方便 UI：

- 在前端重新实现 SQL 行为；

- 绕过现有执行器直接修改底层数据；

- 建立第二套事务逻辑；

- 建立第二套索引维护逻辑；

- 建立第二套 WAL；

- 伪造实验结果。

所有数据库操作最终都必须经过真实 MiniDB 内核。

---

### 2.2 保持现有项目兼容

新增 Studio 后，不破坏：

- 原有 REPL；

- JDBC；

- 数据库文件格式；

- 原有测试；

- 已有示例；

- 已有命令行使用方式。

Web Studio 应作为新的入口存在，而不是替换原有架构。

---

### 2.3 工作库与实验库隔离

用户正常操作的数据库定义为：

> 工作库

事务、恢复、崩溃、性能等教学实验默认使用：

> 独立实验库

避免教学实验破坏用户正常数据库。

界面必须明确显示：

**当前操作对象：工作库 / 实验库**

---

### 2.4 真实结果优先于视觉效果

所有：

- 执行计划；

- B+ 树结构；

- WAL；

- 锁等待；

- Buffer Pool；

- 性能耗时；

- redo / undo；

必须来源于真实内核。

如果某个能力当前内核尚未暴露，则：

> 新增只读观察接口

而不是在前端估算或模拟。

---

# 3. 首版范围

## P0：必须完成

首版至少完成以下闭环：

### 数据库

- 创建数据库；

- 打开数据库；

- 关闭数据库；

- 最近数据库；

- 示例数据库。

### 表

- 查看表列表；

- 查看字段；

- 创建表；

- 删除表；

- 浏览数据；

- 新增记录；

- 编辑记录；

- 删除记录。

### SQL

- SQL 编辑器；

- 执行当前语句；

- 执行选中 SQL；

- 执行整个脚本；

- 查看结果；

- 查看错误；

- 查看历史记录。

### 索引

- 查看索引；

- 创建索引；

- 创建唯一索引；

- 删除索引。

### 事务

- BEGIN；

- COMMIT；

- ROLLBACK；

- 显示当前事务状态。

### 基础教学能力

- SQL AST；

- 执行计划；

- B+ 树观察；

- 双会话事务；

- WAL / 恢复基础实验。

---

## P1：完成 P0 后增加

- Buffer Pool 可视化；

- 锁等待图；

- 死锁实验；

- 索引性能实验；

- JDBC 教学实验；

- SQL 自动补全增强；

- 数据 CSV 导出；

- Schema 信息面板；

- 性能结果保存。

---

## P2：后续版本

首版暂不实现：

- 用户登录；

- 多用户权限；

- 云数据库；

- 网络远程数据库；

- 在线部署；

- 手机专用 UI；

- 数据库 ER 图编辑器；

- SQL AI 助手；

- 插件市场；

- 多数据库同时连接；

- 企业级监控；

- 大规模数据管理。

避免首版范围失控。

---

# 4. 工作台整体结构

采用：

> 顶部状态栏 + 左侧导航 + 主工作区 + 右侧详情抽屉

## 顶部状态栏

始终显示：

```
MiniDB Studio数据库：school.db模式：工作库连接：● 已连接事务：自动提交
```

进入显式事务时：

```
事务：● ACTIVE隔离级别：REPEATABLE READ
```

数据库异常断开时：

```
连接：● 已断开
```

并提供：

> 重新连接

---

# 5. 页面信息架构

左侧一级导航固定为：

```
开始数据库├─ 数据浏览├─ SQL 工作台└─ 索引与计划内核实验室├─ 存储与缓冲池├─ B+ 树├─ SQL 执行流程├─ 事务与锁├─ WAL 与恢复├─ 性能实验└─ JDBC学习与帮助
```

尽量避免一级导航过多。

---

# 6. 开始页

开始页承担整个 Studio 的入口。

页面分为四块。

## 6.1 快速开始

三个主操作：

```
＋ 创建数据库📂 打开数据库🎓 创建示例数据库
```

---

## 6.2 最近数据库

显示：

- 数据库名称；

- 文件路径；

- 最后打开时间；

- 文件是否仍然存在。

点击即可打开。

如果文件已被删除：

```
数据库文件不存在
```

并允许：

> 从最近列表移除

---

## 6.3 新用户引导

首次启动显示：

```
欢迎使用 MiniDB Studio1 创建示例数据库2 浏览 student 表3 执行 SELECT4 创建一个索引5 查看执行计划
```

目标是让第一次使用 MiniDB 的用户几分钟内形成完整认知。

---

# 7. 数据浏览器

整体布局：

```
┌ 表列表 ─────┬──────── 数据区域 ─────────┐│ student     │ 数据 | 结构 | 索引        ││ course      │                           ││ score       │                           │└─────────────┴───────────────────────────┘
```

---

## 7.1 数据视图

支持：

- 分页；

- 排序；

- 简单筛选；

- 新增；

- 编辑；

- 删除；

- 刷新；

- CSV 导出。

默认：

```
50 行 / 页
```

分页必须由 Java 内核完成。

禁止一次加载整张表。

---

## 7.2 精确记录编辑

数据修改必须通过 RID 精确定位记录。

尤其要正确处理：

- 重复行；

- NULL；

- 中文；

- BIGINT；

- VARCHAR；

- VARCHAR 更新导致记录迁移。

如果记录迁移导致 RID 变化：

后端返回：

```
{  "oldRid": "...",  "newRid": "..."}
```

前端同步刷新记录。

如果用户编辑的是已经失效的 RID：

```
该记录已发生变化，请刷新后重试。
```

---

# 8. 建表界面

采用表单方式创建表：

| 字段 | 类型        | NULL |
| ---- | ----------- | ---- |
| id   | INT         | 否   |
| name | VARCHAR(50) | 是   |

支持当前内核真实支持的数据类型。

首版不要在 UI 中承诺当前内核尚未真正实现的约束。

现有计划已经特别指出：

> 当前解析器虽然接受 PRIMARY KEY，但并未真正保存或落实主键约束；唯一性应通过真实唯一索引实现。

因此首版 UI：

不提供虚假的 PRIMARY KEY 语义。

---

# 9. SQL 工作台

这是整个 Studio 的核心页面之一。

页面布局：

```
┌──────── SQL 编辑器 ────────────────┐│ SELECT *                           ││ FROM student                       ││ WHERE id > 10;                     │└────────────────────────────────────┘▶ 执行     ▶ 执行选中     ■ 停止结果 | 消息 | 执行计划 | 历史
```

编辑器采用 CodeMirror。

支持：

- SQL 高亮；

- 行号；

- 当前行；

- 括号匹配；

- Tab；

- SQL 搜索；

- 表名补全；

- 字段名补全。

首版不需要复杂 IDE 级 SQL 智能分析。

---

# 10. SQL 执行规则

## 单语句

返回：

```
列数据耗时影响行数事务状态
```

---

## 多语句脚本

必须由真正的 SQL 词法逻辑拆分。

不能简单：

```
sql.split(";")
```

必须正确处理：

```
INSERT INTO t VALUES ('hello;world');
```

执行策略：

```
语句 1 成功语句 2 成功语句 3 失败停止执行
```

错误信息同时显示：

- 第几个语句；

- 行；

- 列；

- 错误类型；

- 用户可读说明。

---

# 11. 查询结果保护

SQL 结果默认：

```
最大返回 1000 行
```

如果超过：

```
结果超过 1000 行，已截断显示。请增加 WHERE 条件缩小查询范围。
```

限制必须存在于 Java 执行入口。

不能只是前端截断。

这是为了避免：

```
SELECT * FROM huge_table;
```

导致 Java 内存中收集大量结果。

---

# 12. SQL 历史

保存最近执行：

- SQL；

- 时间；

- 数据库；

- 成功 / 失败；

- 耗时。

例如：

```
10:34:21   SELECT * FROM student   12 ms10:31:08   CREATE INDEX ...        18 ms
```

点击历史记录：

> 放回编辑器

首版可存浏览器 localStorage。

不要存敏感数据库内容或大量查询结果。

---

# 13. 事务设计

顶部提供：

```
开始事务提交回滚
```

事务状态：

```
AUTO COMMITACTIVEREAD COMMITTEDACTIVEREPEATABLE READ
```

事务按钮和：

```
BEGINCOMMITROLLBACK
```

必须共享同一个 Session。

禁止出现：

> SQL 已 BEGIN，但顶部依然显示自动提交。

---

# 14. 事务安全规则

显式事务期间：

DDL 按钮禁用。

并提示：

```
当前 MiniDB 的结构变更暂不支持完整事务回滚。请提交或回滚当前事务后再执行 DDL。
```

现有方案已经明确该边界。

---

# 15. 会话模型

定义：

```
Database  ├─ Session A  ├─ Session B  └─ Session C
```

每个浏览器工作会话拥有独立：

- Executor；

- Transaction；

- 当前隔离级别；

- SQL 上下文。

同一路径数据库：

> JVM 内只打开一个 Database 实例。

避免多个 Database 对象同时写同一个文件。

---

# 16. 空闲事务保护

如果浏览器：

- 关闭；

- 崩溃；

- 长时间无操作；

事务不能永久保持。

建议：

```
Session 最大空闲时间：15 分钟
```

超过后：

```
ROLLBACK释放锁释放 Session
```

前端再次操作：

```
会话已过期，未提交事务已自动回滚。
```

---

# 17. 索引与计划页面

索引管理展示：

```
studentidx_student_idUNIQUE(id)idx_student_nameNON-UNIQUE(name)
```

操作：

- 创建；

- 删除；

- 验证；

- 查看结构。

---

# 18. B+ 树可视化

后端增加：

> ReadOnly BPlusTree Snapshot

例如：

```
{  "root": 3,  "nodes": [...]}
```

前端只负责：

> Snapshot → SVG

禁止前端自行读取数据库文件解释 B+ 树。

展示：

```
Root ↓Internal ↓      ↓Leaf ↔ Leaf ↔ Leaf
```

节点点击后显示：

- Page ID；

- Keys；

- 子节点；

- 兄弟节点；

- 使用率。

---

# 19. 执行计划

SQL 页面提供：

```
执行结果执行计划
```

执行计划示例：

```
Projection   │Filter id = 10   │IndexScan idx_student_id
```

没有索引时：

```
Projection   │Filter id = 10   │SeqScan student
```

计划树直接来源于真实 Optimizer / Planner。

---

# 20. 内核实验室

实验室不能只是展示说明文档。

每个实验采用统一结构：

```
实验目标实验环境实验步骤执行内核状态变化实验结果原理解释
```

---

# 21. 存储实验

展示：

```
Database └─ Pages      ├─ Page 12      ├─ Page 13      └─ Page 14
```

观察：

- 4KB 页；

- Page ID；

- 使用空间；

- 空闲空间；

- Slot；

- RID；

- 定长记录；

- 变长记录。

允许：

> 新增记录

随后直接观察页面变化。

---

# 22. Buffer Pool 实验

显示：

| Page | Pin | Dirty | State  |
| ---- | --- | ----- | ------ |
| 12   | 1   | No    | Cached |
| 18   | 0   | Yes   | Cached |

支持受控：

> Flush

实验需要明确展示：

```
读磁盘缓存命中页面变脏写回磁盘
```

---

# 23. SQL 执行流程实验

针对一条 SQL：

```
SELECT nameFROM studentWHERE id = 1;
```

展示：

```
SQL↓Token↓AST↓Logical Plan↓Physical Plan↓Executor↓Result
```

用户可以逐步展开查看。

---

# 24. 双会话事务实验

页面：

```
Session A            Session BBEGIN                BEGINUPDATE ...            SELECT ...状态                   状态持有锁                 等待锁
```

实验模板至少包含：

### 实验 1

READ COMMITTED

### 实验 2

REPEATABLE READ

### 实验 3

锁等待

### 实验 4

死锁

所有步骤使用真实事务管理器。

---

# 25. 锁等待图

例如：

```
T1 │ waits ▼T2 │ waits ▼T1
```

识别死锁后：

```
检测到循环等待Victim：T2T2 已回滚
```

如果当前内核没有结构化查询能力：

增加：

```
LockManagerSnapshot
```

只读接口。

---

# 26. WAL 与恢复实验

恢复实验必须运行：

> 独立实验数据库 + 独立 Java 子进程

标准流程：

```
创建实验库↓执行事务↓写入 WAL↓模拟进程终止↓重新打开数据库↓执行 Recovery↓展示 redo↓展示 undo↓一致性检查
```

不能用：

```
正常关闭数据库
```

冒充：

```
Crash
```

---

# 27. 恢复报告

建议新增结构：

```
RecoveryReport
```

包含：

```
阶段LSNTransaction IDPage ID动作REDO / UNDO结果
```

前端显示真实恢复过程。

---

# 28. 性能实验

默认：

```
10000 行
```

测试：

```
SELECT *FROM studentWHERE id = ?;
```

对比：

```
无索引vsB+ 树索引
```

结果展示：

| 模式    | 执行计划  | 耗时  |
| ------- | --------- | ----- |
| SeqScan | SeqScan   | 18 ms |
| Index   | IndexScan | 2 ms  |

并明确显示：

```
测试环境数据规模执行次数预热情况本次运行时间
```

已有 PERF 文档必须标记：

> 历史测试结果

而不能冒充本次测量。

---

# 29. JDBC 实验

页面包含：

```
代码执行日志数据库变化
```

演示：

- Connection；

- Statement；

- PreparedStatement；

- ResultSet；

- CRUD；

- Transaction。

使用独立实验库。

---

# 30. 学习与帮助

整合现有项目资料：

```
MiniDB 架构01 Storage02 Index03 SQL04 Executor / Optimizer05 Transaction / Recovery06 JDBC
```

不要重新复制大量文档。

优先读取已有 Markdown / 文档。

---

# 31. 前端技术方案

目录：

```
frontend/├─ src/│  ├─ api/│  ├─ components/│  ├─ layouts/│  ├─ pages/│  ├─ features/│  │  ├─ database/│  │  ├─ table/│  │  ├─ sql/│  │  ├─ index/│  │  ├─ transaction/│  │  └─ labs/│  ├─ stores/│  ├─ hooks/│  ├─ types/│  └─ utils/│├─ package.json└─ vite.config.ts
```

技术栈：

```
ReactTypeScriptViteCodeMirrorSVG
```

首版不建议引入重量级 UI 框架。

---

# 32. Java Web 层

新增：

```
minidb.web
```

建议：

```
minidb.web├─ MiniDBWebServer├─ router├─ controller├─ dto├─ session├─ service├─ snapshot└─ error
```

使用：

```
JDK 17 HttpServerJackson
```

Web 层职责：

```
HTTP↓参数校验↓Session↓MiniDB Service↓真实内核
```

不要把数据库业务写进 Controller。

---

# 33. API 设计

统一：

```
/api/v1
```

例如：

```
GET    /api/v1/databasesPOST   /api/v1/databasesPOST   /api/v1/databases/openGET    /api/v1/tablesGET    /api/v1/tables/{name}/rowsPOST   /api/v1/sql/executePOST   /api/v1/transactions/beginPOST   /api/v1/transactions/commitPOST   /api/v1/transactions/rollbackGET    /api/v1/indexesGET    /api/v1/planGET    /api/v1/snapshots/buffer-poolGET    /api/v1/snapshots/btreeGET    /api/v1/snapshots/locks
```

首版接口确定后尽量保持兼容。

---

# 34. 统一响应协议

成功：

```
{  "success": true,  "data": {},  "meta": {    "elapsedMs": 12  }}
```

失败：

```
{  "success": false,  "error": {    "code": "UNIQUE_CONSTRAINT_VIOLATION",    "message": "索引 idx_student_id 要求值唯一。",    "line": 3,    "column": 12  }}
```

禁止把 Java StackTrace 原样展示给普通用户。

开发模式可以保留：

```
Details
```

供调试使用。

---

# 35. BIGINT 与 JSON

BIGINT：

统一使用字符串：

```
{  "value": "9223372036854775807"}
```

避免 JavaScript Number 精度丢失。

---

# 36. 错误码

建议建立统一错误码。

例如：

```
DATABASE_NOT_FOUNDDATABASE_ALREADY_OPENTABLE_NOT_FOUNDCOLUMN_NOT_FOUNDSQL_PARSE_ERRORSQL_EXECUTION_ERRORTRANSACTION_REQUIREDTRANSACTION_ACTIVEUNIQUE_CONSTRAINT_VIOLATIONRID_STALESESSION_EXPIREDRESULT_TRUNCATEDEXPERIMENT_FAILED
```

UI 根据错误码决定交互，而不是解析英文字符串。

---

# 37. 页面状态规范

所有主要页面必须至少处理：

```
LoadingEmptySuccessErrorDisconnected
```

例如表为空：

不要显示：

```
[]
```

而显示：

```
暂无数据＋ 添加第一条记录
```

---

# 38. 危险操作

以下操作二次确认：

```
删除数据库删除表删除索引删除大量记录回滚事务重置实验库
```

按钮使用：

```
删除 student 表
```

而不是模糊的：

```
确定
```

---

# 39. 视觉规范

延续原方案：

```
背景       #F7FAF8内容区     #FFFFFF主色       #23866B边框       浅灰绿色危险       红色
```

设计原则：

- 中文优先；

- 正文 14–16px；

- SQL 使用等宽字体；

- 圆角约 10px；

- 少阴影；

- 增加留白；

- 技术术语提供简短说明；

- 高级设置默认折叠；

- 一个页面突出一个主要动作。

---

# 40. 无障碍与键盘操作

至少支持：

```
Ctrl + Enter执行 SQLCtrl + S保存当前编辑内容Esc关闭抽屉 / 对话框
```

要求：

- 焦点状态清晰；

- 状态不能只依赖颜色；

- 表单存在 label；

- 错误信息可直接阅读。

---

# 41. 响应式策略

项目定位：

> Desktop First

建议最低重点适配：

```
1280 × 7201920 × 1080
```

窗口变窄：

```
左侧导航折叠
```

表格：

```
允许横向滚动
```

首版不做完整移动端设计。

---

# 42. 数据安全

首版默认：

```
127.0.0.1
```

禁止默认监听：

```
0.0.0.0
```

因为 MiniDB Studio 可以：

- 浏览数据库；

- 修改数据；

- 执行 SQL；

- 删除数据。

默认只允许本机访问。

---

# 43. 浏览器刷新恢复

刷新网页后：

前端重新查询：

```
当前 Database当前 SessionTransaction State
```

如果事务还存在：

恢复 UI 状态。

如果 Session 已失效：

提示：

```
原会话已结束，未提交事务已经回滚。
```

---

# 44. Windows 一键启动

最终入口：

```
启动 MiniDB Studio.bat
```

流程：

```
检查 Java↓启动 MiniDB Web Server↓等待端口 Ready↓打开浏览器↓http://127.0.0.1:8080
```

用户不需要：

```
npm run dev
```

正式构建时：

```
frontend build↓Java resources↓JAR
```

最终由 Java Server 提供静态页面。

---

# 45. 开发模式

开发时允许：

```
Vite Dev Server↓/api Proxy↓Java Server
```

正式版本：

```
Browser↓Java HttpServer├─ /│   React static files│└─ /api    MiniDB API
```

---

# 46. 测试体系

测试分为四层。

## 第一层：Java 原有测试

要求：

> 全部继续通过。

---

## 第二层：Web Service 测试

至少覆盖：

```
创建数据库打开数据库SchemaCRUDSQLIndexTransactionSessionSnapshotRecovery
```

---

## 第三层：接口契约测试

重点检查：

```
BIGINTNULL中文错误码事务状态结果截断RIDJSON 类型
```

---

## 第四层：浏览器关键流程

测试：

```
创建数据库↓创建表↓插入数据↓编辑↓查询↓创建索引↓查看 Plan↓BEGIN↓UPDATE↓ROLLBACK↓确认数据恢复
```

---

# 47. 必测边界

数据：

```
NULL空字符串中文BIGINT MAXVARCHAR 长度边界重复行唯一索引冲突
```

存储：

```
VARCHAR 迁移RID 更新重新打开数据库持久化
```

SQL：

```
JOIN聚合子查询脚本字符串中的 ;错误 SQL1000 行截断
```

事务：

```
BEGINCOMMITROLLBACK锁等待死锁Session 超时
```

恢复：

```
Committed TransactionUncommitted TransactionCrashRedoUndo
```

---

# 48. 分阶段实施计划

## Phase 0：项目摸底

先不要写 UI。

完成：

- 确认项目模块；

- 确认 Database 生命周期；

- 确认 Executor；

- 确认 Transaction API；

- 确认 Index API；

- 确认 RID；

- 确认 WAL / Recovery；

- 确认 JDBC；

- 确认现有测试。

输出：

```
MiniDB-Web-Integration-Notes.md
```

---

## Phase 1：最小连接闭环

完成：

```
Web ServerReactGET /api/health创建数据库打开数据库表列表
```

验收：

> 浏览器成功读取真实 MiniDB 数据库。

---

## Phase 2：数据浏览闭环

完成：

```
Schema分页新增编辑删除RID错误处理
```

验收：

> 无需 SQL 即可完成完整 CRUD。

---

## Phase 3：SQL 工作台

完成：

```
CodeMirrorSQL ExecuteScriptResult GridErrorTransaction
```

验收：

> SQL 页面可以完成 MiniDB 主要查询和事务操作。

---

## Phase 4：索引与执行计划

完成：

```
Index CRUDUnique IndexB+ Tree SnapshotPlan Tree
```

---

## Phase 5：内核实验

完成：

```
StorageBuffer PoolSQL PipelineTransactionsLocksWALRecovery
```

---

## Phase 6：性能和 JDBC

完成：

```
Performance LabJDBC Lab
```

---

## Phase 7：产品化收尾

完成：

```
一键启动异常处理空状态响应式帮助README测试浏览器验收
```

---

# 49. 每个阶段的完成标准

任何 Phase 都不能只以：

> “页面已经写好了”

作为完成。

统一 Definition of Done：

```
1 UI 完成2 API 完成3 真实内核连接完成4 异常状态完成5 自动测试完成6 浏览器验证完成7 原有测试没有退化8 文档更新完成
```

满足以上条件才进入下一阶段。

---

# 50. Codex 开发约束

如果后续交给 Codex 实施，建议在任务书明确：

```
禁止一次性大面积重构现有数据库内核。优先调用现有公开接口。确需新增能力时：1 优先新增 Adapter2 再考虑 Service3 最后才修改核心类
```

修改核心内核时必须说明：

```
为什么现有接口无法满足修改了哪些类是否影响文件格式是否影响 REPL是否影响 JDBC是否影响已有测试
```

避免为了前端把 MiniDB 本身改坏。

---

# 51. 建议新增的开发文档

最终项目建议至少包含：

```
docs/├─ studio/│  ├─ architecture.md│  ├─ api.md│  ├─ session-model.md│  ├─ experiment-design.md│  ├─ frontend-design.md│  └─ acceptance-checklist.md
```

这样后续无论交给 Codex、GPT 还是其他模型继续开发，都能快速理解上下文。

---

# 52. 最终交付物

项目完成后至少包含：

```
MiniDB├─ 原 Java 内核├─ frontend/├─ minidb.web/├─ docs/├─ 启动 MiniDB Studio.bat└─ README.md
```

用户最终体验：

```
双击启动 MiniDB Studio.bat↓浏览器自动打开↓创建 / 打开数据库↓可视化管理数据↓SQL 工作台↓查看索引与执行计划↓进入内核实验室
```

---

# 53. 最终验收目标

## 基础用户

完全不会 SQL 的用户也可以：

```
创建库→ 创建表→ 添加数据→ 编辑数据→ 删除数据→ 浏览结果
```

---

## 学习用户

可以完成：

```
SQL→ AST→ Plan→ Executor→ Index→ Transaction→ Lock→ WAL→ Recovery
```

形成完整数据库系统学习链路。

---

## 开发者

可以利用 Studio：

```
观察内核验证 Bug验证执行计划验证事务验证索引验证 Recovery运行性能实验
```

Studio 最终不仅是一个“数据库管理网页”，而应成为：

> **MiniDB 的可视化数据库客户端 + 内核调试器 + 数据库原理实验平台。**

---

# 54. 首版明确不做的事情

为防止开发过程中不断扩大范围，本轮明确：

- 不做账号体系；

- 不做公网服务；

- 不做云数据库；

- 不做多人协作；

- 不做移动端专用应用；

- 不替换现有 JDBC；

- 不替换现有 REPL；

- 不改变已有数据库文件格式；

- 不为了 UI 重写 MiniDB 内核；

- 不伪造任何内核实验结果；

- 不为尚未真正实现的数据库能力提供虚假 UI。

首版优先目标始终是：

> **把 MiniDB 现有真实能力稳定、清晰、可视化地呈现出来。**

在这个基础上，再逐步增强教学、调试和数据库管理体验。##
