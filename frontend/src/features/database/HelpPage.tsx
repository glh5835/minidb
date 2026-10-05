import React from 'react'

/** 学习与帮助（计划书 §30）：整合项目已有文档导航，不复制大量内容 */
export default function HelpPage() {
  return (
    <div>
      <h1 className="page-title">学习与帮助</h1>
      <p className="page-sub">MiniDB 架构速览 + 内核实验室使用指南 + 完整文档索引</p>

      <div className="card">
        <h2>MiniDB 架构（一分钟）</h2>
        <div className="flow" style={{ marginBottom: 12 }}>
          {['SQL 文本', 'Lexer → Parser → AST', 'Optimizer（代价估算）', '火山算子树 Executor', 'BufferPool（4KB 页）', 'DiskManager（数据库文件 + WAL）'].map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <span className="arrow">→</span>}
              <span className="node">{s}</span>
            </React.Fragment>
          ))}
        </div>
        <ul style={{ fontSize: 13.5, lineHeight: 2 }}>
          <li><b>01 Storage</b>：Database 门面 · 4KB 页 · 页链表 · 定长/变长记录 · RID = (页, 槽)</li>
          <li><b>02 Index</b>：B+ 树唯一索引 · 变长键（≤255 字节）· 叶链支持范围扫描</li>
          <li><b>03 SQL</b>：手写词法/语法分析 → 不可变 AST（Studio 的脚本拆分同样基于真词法）</li>
          <li><b>04 Executor / Optimizer</b>：火山模型 · 谓词下推 · 索引选择 · 贪心 JOIN 顺序 · Hash Join</li>
          <li><b>05 Transaction / Recovery</b>：严格 2PL 行锁 · 等待图死锁检测 · WAL（steal + no-force，简化 ARIES：redo + undo）</li>
          <li><b>06 JDBC</b>：jdbc:minidb: 文件路径 · 标准 Connection/Statement/PreparedStatement</li>
        </ul>
      </div>

      <div className="card">
        <h2>Studio 使用提示</h2>
        <ul style={{ fontSize: 13.5, lineHeight: 2 }}>
          <li><b>键盘</b>：Ctrl+Enter 执行 SQL · Esc 关闭对话框/抽屉</li>
          <li><b>工作库 vs 实验库</b>：日常操作用工作库；事务/锁/恢复/性能等实验自动使用独立实验库（<span className="mono">*.exp.db</span>），互不干扰</li>
          <li><b>RID 精确编辑</b>：数据浏览的编辑/删除按 RID 定位；若记录已迁移或被删除会提示刷新</li>
          <li><b>BIGINT</b>：JSON 中以字符串传输，避免 JS 精度丢失（如 9223372036854775807）</li>
          <li><b>结果上限</b>：SQL 查询最多返回 1000 行（内核执行入口截断，超限会有提示）</li>
          <li><b>会话</b>：浏览器刷新后自动恢复会话；空闲 15 分钟的会话会被回收，未提交事务自动回滚</li>
          <li><b>事务边界</b>：DDL（建表/删表/建删索引）不走事务日志，显式事务期间拒绝执行</li>
          <li><b>唯一索引</b>：MiniDB 的索引全部是唯一索引；NULL 不入索引；没有虚假的 PRIMARY KEY 语义</li>
        </ul>
      </div>

      <div className="card">
        <h2>完整文档（项目仓库）</h2>
        <div className="table-wrap">
          <table className="grid">
            <thead><tr><th>文档</th><th>内容</th></tr></thead>
            <tbody>
              <tr><td className="mono">MiniDB-Web-Integration-Notes.md</td><td>内核 API 摸底与 Web 集成决定（Phase 0 产出）</td></tr>
              <tr><td className="mono">docs/studio/architecture.md</td><td>Studio 架构与分层</td></tr>
              <tr><td className="mono">docs/studio/api.md</td><td>HTTP API 完整参考</td></tr>
              <tr><td className="mono">docs/studio/session-model.md</td><td>会话/事务/数据库注册表模型</td></tr>
              <tr><td className="mono">docs/studio/experiment-design.md</td><td>实验室每个实验的设计与"真实内核"边界</td></tr>
              <tr><td className="mono">docs/01-存储层设计.md … 06-JDBC驱动设计.md</td><td>内核六阶段设计文档</td></tr>
              <tr><td className="mono">docs/07-从零写数据库：我踩过的坑.md</td><td>实现经验与教训</td></tr>
              <tr><td className="mono">PERF.md</td><td>历史性能测试结果（非实时数据）</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h2>SQL 方言速查</h2>
        <pre className="code">
{`-- DDL（不支持事务回滚）
CREATE TABLE t (id INT, name VARCHAR(50), big BIGINT, score DOUBLE);
DROP TABLE t;
CREATE INDEX idx_name ON t(column);
DROP INDEX idx_name;

-- DML
INSERT INTO t VALUES (1, '张三', 9223372036854775807, 88.5);
UPDATE t SET score = 90 WHERE id = 1;
DELETE FROM t WHERE id = 1;

-- 查询（支持 JOIN / GROUP BY / 聚合 / 子查询 / LIKE / BETWEEN / ORDER BY / LIMIT）
SELECT s.name, c.title FROM student s JOIN score sc ON s.id = sc.student_id
  JOIN course c ON c.id = sc.course_id WHERE sc.grade >= 80 ORDER BY sc.grade DESC;

-- 事务（READ COMMITTED / REPEATABLE READ）
BEGIN; UPDATE ...; COMMIT;`}
        </pre>
      </div>
    </div>
  )
}
