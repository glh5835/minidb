# MiniDB 设计文档 06：JDBC 驱动

> 对应阶段 6。代码：`minidb.jdbc`（8 个类，约 2200 行）+ `minidb.demo.JdbcDemo` + `META-INF/services/java.sql.Driver`。测试：JdbcTest 35 项。

## 1. 目标与边界

目标：**任何只依赖 `java.sql.*` 的标准代码**（包括第三方连接池、报表工具的教学版）能连上 MiniDB 跑 CRUD 与事务。边界：单机嵌入式（URL 即文件路径），无网络协议、无 CallableStatement、无流/大对象/日期时间高级类型——这些方法要么抛 `SQLException`（语义可表达），要么由 `AbstractUnsupportedResultSet` 统一抛 `SQLFeatureNotSupportedException`（语义不可表达）。

## 2. 类清单与职责

| 类 | 职责 |
|----|------|
| `MiniDbDriver` | URL 前缀 `jdbc:minidb:`；static 块自注册 + ServiceLoader 双保险；`jdbcCompliant()=false`（诚实） |
| `MiniDbConnection` | 持有 `Database + Executor`；autoCommit 状态机；显式事务委托 Executor；close 回滚未提交事务 |
| `MiniDbStatement` | 静态 SQL：parse → executor.execute；DML 行数从消息解析；异常翻译 |
| `MiniDbPreparedStatement` | 词法级 `?` 占位符绑定（引号感知）+ 参数文本化渲染 + 批处理 |
| `MiniDbResultSet` | 物化结果集：游标/wasNull/按名取列/类型转换 |
| `MiniDbResultSetMetaData` | 列数/列名/类型映射 |
| `MiniDbDatabaseMetaData` | 产品名/驱动名/版本/表清单（`usesLocalFiles()=true` 等） |
| `AbstractUnsupportedResultSet` | 199 行模板：ResultSet 接口里用不到的方法统一抛不支持，让子类只写有语义的部分 |

## 3. 关键设计

### 3.1 驱动注册（双通道）

1. `META-INF/services/java.sql.Driver` 声明 `minidb.jdbc.MiniDbDriver`——`DriverManager` 初始化时经 ServiceLoader 自动加载（测试 `driverRegisteredViaServiceLoader` 验证不显式 `Class.forName` 也能连）；
2. static 块里 `DriverManager.registerDriver(new MiniDbDriver())` 兜底（类被显式加载时也注册）。

`connect()` 对不认识的 URL **返回 null 而非抛异常**——这是 JDBC 契约：DriverManager 逐个询问多个驱动，null 表示"不是我家的"。非法 `jdbc:minidb:`（空路径）才抛 SQLException。

### 3.2 PreparedStatement：参数文本化（本层最重要的取舍）

不做二进制协议/预编译计划，而是在**词法层**做 `?` 替换：

- `countPlaceholders(sql)` **引号感知**地数 `?`（字符串字面量里的 `?` 不算占位符）；
- `setXxx(i, v)` 存进 `Object[] params`；`boundSql()` 渲染时字符串走 `''` 转义、数字直拼——**字符串自动转义即是防注入**（测试用 `it's a 'quote'` 验证往返）；
- `checkAllSet()`：有未绑定的 `?` 就抛 SQLException（`unboundParameterThrows` / `clearParametersResets`）；
- `addBatch()` 把当次渲染好的 SQL 字符串入列表，`executeBatch()` 逐条执行返回计数数组。

为什么选文本化：MiniDB 没有服务端，"预编译"没有跨网络复用计划的需求；参数渲染成 SQL 后完全复用 Statement 路径，实现量最小且语义与直接写 SQL 严格一致。代价：每次执行重新解析 SQL（PERF.md 显示解析+计划构造只占点查 1.55 µs 的一部分，教学场景可接受）；类型安全靠 `literal()` 渲染规则保证。

### 3.3 事务映射

- `autoCommit=true`（默认）：每条语句即一个 RC 事务（Executor 语句级 autoCommit）；
- `setAutoCommit(false)`：开 `TxnSession` 显式事务（默认 REPEATABLE_READ），`commit()/rollback()` 委托 Executor；
- **close 时未提交事务自动回滚**（`closeRollsBackOpenTransaction` 用重开连接验证行数）；
- 在 autoCommit=true 下调 commit/rollback 抛 SQLException（JDBC 语义）。

### 3.4 异常翻译（不泄漏实现细节）

`MiniDbStatement.execute` 的 catch 链：

```java
} catch (MiniDbException e) {            // 解析/执行错误
    throw new SQLException(e.getMessage(), e);
} catch (RuntimeException e) {           // 存储层约束，如 NPE("INT 列不接受 NULL")
    throw new SQLException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
}
```

第二个 catch 是阶段 6 收尾时踩出来的坑：`setNull` 插 INT 列时存储层抛 `NullPointerException` 直接穿透到调用方——JDBC 驱动契约要求一切失败都以 `SQLException` 呈现，补上后 `setNullProducesNull` 用例才绿。

### 3.5 结果集（物化 + 游标语义）

- Executor 的 `Result` 已是 `List<Object[]>` 全物化，ResultSet 直接持有，`pos=-1` 起步，`next()` 前取值抛异常（`cursorWithoutNextThrows`）；
- `resolve(columnLabel)` 支持裸列名与 `t.col` 限定名，找不到抛 SQLException；
- `wasNull()` 在每次 `value()` 取值时刷新；类型转换宽容（`getInt` 收 Long/Double 带截断检查、`getString` 万物可转）；
- `getMetaData()` 列名/列类型从 Executor columns 映射到 `java.sql.Types`。

## 4. Demo（minidb.demo.JdbcDemo）

77 行纯 `java.sql.*` 代码：ServiceLoader 自动发现驱动 → 建表（`IF NOT EXISTS`，幂等）→ PreparedStatement 插 3 行 → 查询遍历 → UPDATE → DELETE → 显式事务（转账式两条 UPDATE + commit）→ 汇总断言。**跨 JVM 重启数据持久**已人工验证：连续运行两次，第二次在已有数据上叠加且建表不报错。

运行：`java -cp target/classes minidb.demo.JdbcDemo [jdbc:minidb:path]`

## 5. 测试（35 项）

- 驱动/连接 7：ServiceLoader 注册、URL 识别、坏 URL 抛错、建库即建文件、autoCommit 默认值、close 失效、MetaData 基本项；
- Statement/ResultSet 12：查询遍历、execute 返回值语义（DML false + updateCount）、executeQuery 打在 DML 上抛错、语法错误翻译、游标语义（isBeforeFirst/isFirst/getRow/闭后 next）、列类型取值、MetaData 列数列名、findColumn 裸/限定名、未知列抛错、关闭的 Statement 抛错；
- PreparedStatement 7：绑定插入查询、**字符串转义往返**、未绑定抛错、setNull、批处理计数、clearParameters、参数复用重执行；
- 事务 5：跨连接可见性、回滚撤销、autocommit 独立、无事务时 commit 抛错、close 回滚；
- DDL/持久化 4：IF NOT EXISTS 幂等、经 JDBC 建索引查询、DROP 后查询失败、跨连接持久。

## 6. 取舍

| 决策 | 理由 | 代价 |
|------|------|------|
| 参数文本化而非预编译协议 | 无网络层，复用 Statement 路径实现最小 | 每次执行重新解析；无语句缓存 |
| 结果集物化 | Executor 已物化；游标语义简单精确 | 大结果集全内存 |
| AbstractUnsupportedResultSet 模板 | ResultSet 接口 ~200 方法，集中声明不支持 | 不可用方法的错误在运行期才出现 |
| updateCount 从消息文本解析 | 复用 Executor 的 Result.message 协议 | 层间约定脆弱（消息改字即坏）——文档 07 有反思 |
| 无连接池/网络服务 | 教学边界 | 仅进程内使用 |
