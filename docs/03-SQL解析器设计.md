# MiniDB 设计文档 03：SQL 解析器（词法 + 语法 + AST）

> 对应阶段 3 前半。代码：`minidb.sql.{Token, Lexer, Parser, Ast}`，合计约 830 行，零依赖（不用 ANTLR）。测试：LexerTest 12 + ParserTest 28 = 40 项。

## 1. 总体流水线

```
SQL 文本 --Lexer.tokenize()--> List<Token>（一次性切词，尾部补 EOF）
         --Parser（递归下降，游标 peek/next/accept/expect）--> AST（Java record）
         --Executor（阶段3后半，见文档04）--> 计划 --> 结果
```

两遍结构：词法一遍扫完，语法阶段只在 `List<Token>` 上前进，不回看字符。这使解析器代码极对称：每个子句一个私有方法，`expect(TABLE)` / `accept(WHERE)` 四件套贯穿。

## 2. 词法分析器（Lexer，192 行）

- **Token.Type 共 77 个枚举值**：字面量 3（IDENT/NUMBER/STRING）+ 运算符标点 16 + 关键字 57 + EOF；`KEYWORDS` 不可变 Map 共 56 个条目。
- 标识符：`字母|_` 开头，续 `字母|数字|_`；**关键字大小写不敏感**（识别后归一小写），普通标识符保留原大小写。
- 数字：数字与至多一个 `.`，第二个点报错；**不支持科学计数法**；**负数不在词法层处理**——词法只出 `MINUS`，由解析器组成一元 `UnaryOp("-")`（正确的分层：`-3` 是表达式不是字面量）。
- 字符串：仅单引号，转义只有 SQL 标准 `''`（无反斜杠转义）；未闭合报"字符串缺少结束引号"。
- 两字符前瞻：`!=` `<=` `>=` `<>`（`<>` 归并为 NEQ，与 `!=` 同型）；孤立 `!` 报错。
- 注释：仅 `--` 行注释。
- **错误报告**：统一 `Token.err(msg, pos)` → `MiniDbException(Code.PARSE)`，消息带出错原文与 `(位置 N)` 字符偏移；**不算行列号**（已知取舍，见 §6）。

## 3. 语法分析器（Parser，495 行）

`statement()` 按首词元 switch 分发：

| 语句 | 支持形式 |
|------|----------|
| SELECT | `SELECT [DISTINCT] items [FROM ...] [WHERE] [GROUP BY] [HAVING] [ORDER BY ...] [LIMIT n [OFFSET m]]`；无 FROM 的 `SELECT 1+1` 合法（带 WHERE 等子句则显式报错） |
| FROM | 逗号笛卡尔积、`INNER JOIN ... ON`、`LEFT [OUTER] JOIN ... ON`（ON 强制）；不支持 RIGHT/FULL |
| INSERT | `INSERT INTO t [(cols)] VALUES (...), (...)`（多行） |
| UPDATE | `UPDATE t SET col=expr, ... [WHERE]` |
| DELETE | `DELETE FROM t [WHERE]` |
| CREATE | `CREATE TABLE [IF NOT EXISTS] t (col TYPE[(n)] [PRIMARY KEY], ...)`、`CREATE INDEX i ON t(col)` |
| DROP | `DROP TABLE t` / `DROP INDEX i` |
| 事务 | `BEGIN` / `COMMIT` / `ROLLBACK` |

类型别名归一化：`INT/INTEGER→INT`、`BIGINT/LONG→BIGINT`、`DOUBLE/FLOAT→DOUBLE`、`VARCHAR/STRING→VARCHAR`。

### 表达式优先级（低 → 高，每级一个方法）

```
OR → AND → NOT(前缀) → 比较/IN/LIKE/BETWEEN/IS → + - → * / % → 一元负号 → 原子
```

- OR/AND/加减/乘除用 while 循环**左结合**；NOT 与负号右递归。
- 比较层在一个循环里处理：六个比较符、`LIKE`（模式须为字符串字面量）、`IN (列表 | 子查询)`、`BETWEEN a AND b`、`IS [NOT] NULL`，以及 `NOT LIKE/IN/BETWEEN` 变体。
- 原子：数字（int→long 溢出检查）、字符串、NULL、括号、**标量子查询** `(SELECT...)`、`EXISTS (...)`、聚合 `COUNT(*)/COUNT(expr)/SUM/AVG/MIN/MAX`（这五个是关键字词元，不能作列名）、`t.col` 限定列与裸列。
- 尾随分号接受；语句后有垃圾报错（`expect(EOF)`）。

## 4. AST（Ast.java，100 行）

**全部 Java record + 两个 sealed 接口**：

- `sealed interface Expr`（12 节点）：`Literal / ColRef / BinOp / UnaryOp / LikeOp / InListOp / InQueryOp / ExistsOp / ScalarQueryOp / BetweenOp / IsNullOp / FuncCall`；
- `sealed interface TableRef permits NamedTable, Join`，带 `leftmostTable()` 供连接顺序推导；`Join(left, right, "INNER"|"LEFT", on)`，`on == null` 即笛卡尔积；
- 语句：`SelectStmt`（9 字段）、`ColumnDef`、`CreateTableStmt(table, columns, ifNotExists)`、`InsertStmt`、`UpdateStmt`（内嵌 `Assign`）、`DeleteStmt`、`CreateIndexStmt / DropIndexStmt / DropTableStmt`。

合计 22 个 record + 2 个 sealed 接口。**不可变 + sealed** 让执行器用 `switch` 模式匹配分派时由编译器保证穷尽——加节点忘改分派会直接编译错误，这在阶段 3→5 三次扩展（加 IF NOT EXISTS、事务、子查询）中反复受益。

**一个务实的特例**：BEGIN/COMMIT/ROLLBACK 直接返回 `Token.Type` 枚举而非 AST 节点，因此 `parseStatement()` 返回 `Object`。三单词语句不值得造三个 record，代价是失去类型安全（Executor 侧用 `instanceof Token.Type` 分流）。

## 5. 测试（40 项）

- **LexerTest 12**：大小写归一、前导零、`''` 转义、13 个运算符逐一、注释忽略（断言 token 数）、三种故障注入（未闭合串/非法字符/孤立 `!`）、位置追踪、端到端切词。
- **ParserTest 28**：对 **AST 结构**（而非字符串）断言——`flatAnd()` 辅助方法把左结合 AND 链展平后逐索引断言节点类型；`1+2*3-4/2` 的树形断言验证优先级；AND 高于 OR；JOIN 树形断言（嵌套结构）；三类子查询；聚合五函数；GROUP BY+HAVING+ORDER+LIMIT/OFFSET 全子句；6 个错误注入（缺括号、垃圾尾巴、无 FROM 带 WHERE 等）。

## 6. 设计取舍（为什么不用 ANTLR）

1. **手写的理由**：任务书明令不用 ANTLR。830 行拿到一门完整 SQL 子集的解析器，无生成代码、无运行时依赖，优先级/递归下降原理对教学完全可见。代价：加一种语法要同时改 Lexer 关键字表、Token 枚举、Parser 方法、AST record 四处（IF NOT EXISTS 的加入就是一次完整演练）。
2. **fail-fast 无恢复**：首错即抛，不做 panic-mode 错误同步点、不产多条错误。REPL 一次报一个错够用；批量脚本场景（JDBC batch）由上层逐条执行兜住。
3. **错误位置只有字符偏移**：没有行列换算。SQL 都是单行为主，够用；列为未来工作。
4. **PRIMARY KEY 语法接受但不入 AST**：词法消费即丢弃，为元数据层演进留口（当前无主键约束语义）。
5. **已知限制**：无科学计数法/块注释/引号标识符；无 RIGHT/FULL JOIN；无 `LIMIT o,n` 变体；比较运算可被意外链式解析（`a=b=c` 合法但语义怪）；`UNQUOTED_EOF` 为遗留未用枚举值。

## 7. 演进记录

- 阶段 3 初版：六语句 + 全部 SELECT 子句。
- 阶段 5：BEGIN/COMMIT/ROLLBACK（Token.Type 直通）。
- 阶段 6：`IF` 关键字 + `CREATE TABLE IF NOT EXISTS`（JDBC 幂等建表需要）——一次"四处同改"的标准扩展样例。
