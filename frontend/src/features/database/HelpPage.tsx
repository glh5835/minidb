import { Fragment } from 'react'
import { StudioIcon } from '../../components/StudioIcon'
import './HelpPage.css'

const learningSteps = [
  { title: '认识工作台', description: '打开示例库，浏览表并执行 SQL。' },
  { title: '跟踪 SQL 与索引', description: '从 Token、AST 追到执行计划和结果。' },
  { title: '理解存储与事务', description: '观察数据页、行锁和 WAL 恢复。' },
  { title: '对比性能与调用 JDBC', description: '测量索引点查，验证提交与回滚。' },
]

const labs = [
  { path: '/lab/storage', title: '存储与缓冲池', icon: 'layers', description: '查看 4 KB 页、RID 和缓冲池状态' },
  { path: '/lab/pipeline', title: 'SQL 执行流程', icon: 'activity', description: '查看 Token、AST、计划和结果' },
  { path: '/lab/txn', title: '事务与锁', icon: 'lock', description: '用双会话观察锁等待和死锁' },
  { path: '/lab/recovery', title: 'WAL 与恢复', icon: 'rotate', description: '模拟崩溃，查看 REDO 与 UNDO' },
  { path: '/lab/perf', title: '性能实验', icon: 'gauge', description: '实测 SeqScan 与 IndexScan' },
  { path: '/lab/jdbc', title: 'JDBC', icon: 'plug', description: '查看驱动调用、回滚与最终数据' },
] as const

const repository = 'https://github.com/glh5835/minidb'
const documentGroups = [
  {
    title: 'Studio 手册',
    documents: [
      { title: 'Studio 架构', path: 'docs/studio/architecture.md' },
      { title: 'HTTP API 参考', path: 'docs/studio/api.md' },
      { title: '会话模型', path: 'docs/studio/session-model.md' },
      { title: '实验设计', path: 'docs/studio/experiment-design.md' },
      { title: 'Web 集成笔记', path: 'MiniDB-Web-Integration-Notes.md' },
    ],
  },
  {
    title: '内核与性能',
    documents: [
      { title: '内核六阶段设计', path: 'docs/' },
      { title: '数据库开发踩坑总结', path: 'docs/07-从零写数据库：我踩过的坑.md' },
      { title: '历史性能报告', path: 'PERF.md' },
    ],
  },
]

const architecture = [
  'SQL 文本',
  'Lexer → Parser → AST',
  'Optimizer（代价估算）',
  '火山算子树 Executor',
  'BufferPool（4 KB 页）',
  'DiskManager（数据库文件 + WAL）',
]

export default function HelpPage() {
  return (
    <div className="help-view">
      <header className="help-intro">
        <h1>学习与帮助</h1>
        <p>从第一条 SQL 到数据库内核，按路线学习、动手做实验，再查阅设计文档。</p>
      </header>

      <section className="card help-route" aria-labelledby="help-route-title">
        <div className="help-panel-head">
          <div>
            <h2 id="help-route-title"><StudioIcon name="tree" />学习路线</h2>
            <p>按顺序从工作台入门，逐步理解一条 SQL 如何执行、落盘和恢复。</p>
          </div>
          <span className="help-pill">建议顺序</span>
        </div>
        <ol className="help-steps">
          {learningSteps.map(({ title, description }, index) => (
            <li className={index === 0 ? 'help-step start' : 'help-step'} key={title}>
              <span className="help-step-number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
              <h3>{title}</h3>
              <p>{description}</p>
            </li>
          ))}
        </ol>
      </section>

      <div className="help-columns">
        <section className="card help-lab" aria-labelledby="help-lab-title">
          <div className="help-panel-head">
            <div>
              <h2 id="help-lab-title"><StudioIcon name="flask" />实验入口</h2>
              <p>按主题直接进入内核实验室。</p>
            </div>
            <span className="help-pill lab">独立实验库</span>
          </div>
          <ul className="help-labs">
            {labs.map(({ path, title, icon, description }) => (
              <li key={path}>
                <a className="help-lab-tile" href={'#' + path}>
                  <span className="help-lab-icon"><StudioIcon name={icon} /></span>
                  <div><h3>{title}</h3><p>{description}</p></div>
                  <StudioIcon name="chevron" />
                </a>
              </li>
            ))}
          </ul>
          <div className="help-quick">
            <div className="help-quick-title"><StudioIcon name="terminal" /><strong>SQL 工作台快捷键</strong></div>
            <div className="help-keys">
              <span><kbd>Ctrl</kbd><span className="help-plus">+</span><kbd>Enter</kbd> 执行 SQL</span>
              <span><kbd>Esc</kbd> 关闭弹窗或抽屉</span>
            </div>
          </div>
        </section>

        <section className="card help-docs" aria-labelledby="help-docs-title">
          <div className="help-panel-head">
            <div>
              <h2 id="help-docs-title"><StudioIcon name="book" />文档链接</h2>
              <p>按主题查阅项目仓库里的原文。</p>
            </div>
            <span className="help-pill">项目仓库</span>
          </div>
          {documentGroups.map(({ title, documents }) => (
            <div className="help-doc-group" key={title}>
              <h3>{title}</h3>
              <ul className="help-links">
                {documents.map(({ title: documentTitle, path }) => {
                  const directory = path.endsWith('/')
                  const encodedPath = path.replace(/\/$/, '').split('/').map(encodeURIComponent).join('/')
                  const href = `${repository}/${directory ? 'tree' : 'blob'}/main/${encodedPath}`
                  const filename = directory ? path : path.slice(path.lastIndexOf('/') + 1)
                  return (
                    <li key={path}>
                      <a href={href} target="_blank" rel="noopener noreferrer" title={path} aria-label={`${documentTitle}（在新标签页打开）`}>
                        <StudioIcon name={directory ? 'folder' : 'file'} />
                        <strong>{documentTitle}</strong>
                        <code>{filename}</code>
                        <StudioIcon name="arrow-right" className="help-out" />
                      </a>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        </section>
      </div>

      <HelpReferences />
    </div>
  )
}

function HelpReferences() {
  return (
    <section className="card help-references" aria-label="架构速览与速查">
      <details>
        <summary>SQL 方言速查</summary>
        <div className="help-reference-body">
          <pre className="code"><code>{`-- DDL（不支持事务回滚）
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
BEGIN; UPDATE ...; COMMIT;`}</code></pre>
        </div>
      </details>
      <details>
        <summary>Studio 使用提示</summary>
        <div className="help-reference-body">
          <ul>
            <li><b>键盘</b> · Ctrl+Enter 执行 SQL，Esc 关闭对话框或抽屉。</li>
            <li><b>工作库与实验库</b> · 日常操作用工作库。事务、锁、恢复、性能等实验使用独立的 <code>*.exp.db</code> 文件。</li>
            <li><b>RID 精确编辑</b> · 数据浏览的编辑和删除按 RID 定位。记录迁移或删除后会提示刷新。</li>
            <li><b>BIGINT</b> · JSON 以字符串传输，避免 JS 精度丢失，例如 <code>9223372036854775807</code>。</li>
            <li><b>结果上限</b> · SQL 查询最多返回 1000 行。内核执行入口截断时会提示。</li>
            <li><b>会话</b> · 刷新浏览器会自动恢复会话。空闲 15 分钟后会话被回收，未提交事务自动回滚。</li>
            <li><b>事务边界</b> · DDL（建表、删表、建删索引）不走事务日志，显式事务期间拒绝执行。</li>
            <li><b>唯一索引</b> · MiniDB 的索引均为唯一索引，NULL 不入索引。<code>PRIMARY KEY</code> 声明不会自动建索引。</li>
          </ul>
        </div>
      </details>
      <details>
        <summary>MiniDB 架构速览</summary>
        <div className="help-reference-body">
          <div className="flow">
            {architecture.map((stage, index) => (
              <Fragment key={stage}>
                {index > 0 && <span className="arrow" aria-hidden="true">→</span>}
                <span className="node">{stage}</span>
              </Fragment>
            ))}
          </div>
          <ul>
            <li><b>01 Storage</b> · Database 门面、4 KB 页、页链表、定长与变长记录、RID = (页, 槽)。</li>
            <li><b>02 Index</b> · B+ 树唯一索引，变长键上限 255 字节，叶链支持范围扫描。</li>
            <li><b>03 SQL</b> · 手写词法和语法分析生成不可变 AST。Studio 的脚本拆分也使用内核词法分析器。</li>
            <li><b>04 Executor / Optimizer</b> · 火山模型、谓词下推、索引选择、贪心 JOIN 顺序和 Hash Join。</li>
            <li><b>05 Transaction / Recovery</b> · 严格 2PL 行锁、等待图死锁检测、WAL（steal + no-force，简化 ARIES 的 REDO 与 UNDO）。</li>
            <li><b>06 JDBC</b> · <code>jdbc:minidb:</code> 文件路径与标准 Connection、Statement、PreparedStatement。</li>
          </ul>
        </div>
      </details>
    </section>
  )
}
