import { useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox } from '../../components/ui'
import { useApp } from '../../stores/app'
import './SqlPipelineLab.css'

type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

interface PipelineResult {
  tokens?: { type: string; text: string; pos: number }[]
  ast?: JsonValue
  planText?: string
  columns?: string[]
  rows?: unknown[][]
  rowCount?: number
  truncated?: boolean
  elapsedMs?: number
}

const stages = [
  { id: 'tokens', label: 'Token', caption: '词法分析', title: 'Token 词法分析', icon: 'scan',
    detail: 'Lexer 将 SQL 文本拆成带类型和位置的 Token。', hint: '位置是后端词法输入中的字符偏移量，从 0 开始。' },
  { id: 'ast', label: 'AST', caption: '语法分析', title: 'AST 语法树', icon: 'tree',
    detail: '解析器将 Token 组织为语句、表引用和表达式。', hint: '语法树描述查询结构，优化器接着选择访问路径和算子。' },
  { id: 'plan', label: '执行计划', caption: '优化器', title: '执行计划', icon: 'layers',
    detail: 'MiniDB 优化器生成的算子树，缩进表示父子关系。', hint: '执行器沿算子树拉取数据，生成下一阶段的查询结果。' },
  { id: 'result', label: '结果', caption: '执行器', title: '查询结果', icon: 'grid',
    detail: '执行器按计划读取数据。行与列来自本次管线响应。', hint: '显示的耗时包含词法分析、解析、计划生成与查询执行。' },
] as const

type StageId = (typeof stages)[number]['id']

interface AstBranch {
  field: string
  kind?: string
  summary?: string
  children: AstBranch[]
}

function isObject(value: JsonValue | undefined): value is { [key: string]: JsonValue } {
  return value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value)
}

function astBranches(value: JsonValue, field = ''): AstBranch[] {
  if (Array.isArray(value)) {
    return value.flatMap((child, i) => astBranches(child, field + '[' + i + ']'))
  }
  if (!isObject(value)) return [{ field, summary: String(value), children: [] }]

  const kind = typeof value.node === 'string' ? value.node : undefined
  const properties = Object.entries(value).filter(([key]) => key !== 'node' || !kind)
  const attributes = properties.filter(([, child]) => child === null || typeof child !== 'object')
  const isWrapper = !kind && !attributes.length
  const children = properties
    .filter(([, child]) => child !== null && typeof child === 'object')
    .flatMap(([key, child]) => astBranches(child, isWrapper && field ? field + '.' + key : key))
  if (isWrapper) return children

  const summary = attributes
    .filter(([key, child]) => key !== 'valueType' && !(key === 'distinct' && child === false))
    .map(([key, child]) => kind && ['column', 'name', 'table', 'target', 'op', 'value'].includes(key)
      ? String(child) : key + '=' + String(child))
    .join(' · ')
  return [{ field, kind, summary, children }]
}

function AstTree({ branches }: { branches: AstBranch[] }) {
  return (
    <ul className="pipe-tree">
      {branches.map(({ field, kind, summary, children }, index) => (
        <li key={field || index}>
          <span className={'pipe-tree-node' + (!field ? ' pipe-tree-root' : '')}>
            {field && <span className="pipe-tree-field">{field}</span>}
            {kind && <b>{kind}</b>}
            {summary && <span>{summary}</span>}
          </span>
          {children.length > 0 && <AstTree branches={children} />}
        </li>
      ))}
    </ul>
  )
}

function formatAst(value: JsonValue, depth = 0): string {
  const indent = '  '.repeat(depth)
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]'
    return '[\n' + value.map((item) => indent + '  ' + formatAst(item, depth + 1)).join(',\n') + '\n' + indent + ']'
  }
  if (!isObject(value)) return JSON.stringify(value)
  const entries = Object.entries(value)
  if (entries.length === 0) return '{}'
  const parts = entries.map(([key, child]) => JSON.stringify(key) + ': ' + formatAst(child, depth + 1))
  const inline = '{ ' + parts.join(', ') + ' }'
  if (depth > 0 && !inline.includes('\n') && inline.length <= 65) return inline
  return '{\n' + parts.map((part) => indent + '  ' + part).join(',\n') + '\n' + indent + '}'
}

function AstJson({ ast }: { ast: JsonValue }) {
  const parts = formatAst(ast).split(/("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\b(?:true|false|null)\b)/g)
  return (
    <pre className="pipe-json" tabIndex={0} aria-label="AST JSON">
      {parts.map((part, i) => {
        if (i % 2 === 0) return part
        const style = part.startsWith('"')
          ? (parts[i + 1]?.startsWith(':') ? 'pipe-json-key' : 'pipe-json-value')
          : 'pipe-json-number'
        return <span key={i} className={style}>{part}</span>
      })}
    </pre>
  )
}

/** 展示同一条 SQL 经过真实内核的词法、语法、优化与执行阶段。 */
export default function SqlPipelineLab() {
  const { connected, ready, session } = useApp()
  const [sql, setSql] = useState('SELECT name FROM student WHERE id = 1;')
  const [lastRun, setLastRun] = useState<{ sql: string; response: PipelineResult } | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const [stage, setStage] = useState<StageId>('tokens')
  const result = lastRun?.response
  const stageIndex = stages.findIndex(({ id }) => id === stage)
  const active = stages[stageIndex]
  const dbPath = connected && session?.hasDb ? session.dbPath : null
  const databaseName = dbPath?.split(/[\\/]/).pop()
  const astKind = isObject(result?.ast) && typeof result.ast.node === 'string' ? result.ast.node : undefined
  const tokens = Array.isArray(result?.tokens) ? result.tokens : undefined
  const rowCount = Array.isArray(result?.rows) && Array.isArray(result.columns) ? result.rowCount ?? result.rows.length : undefined
  const stageMetadata = {
    tokens: tokens && (tokens.length + ' 项' + (tokens.some(({ type }) => type === 'EOF') ? '（含 EOF）' : '')),
    ast: astKind,
    plan: result?.planText && (astKind === 'SelectStmt' ? '算子树' : astKind ? '仅 SELECT' : undefined),
    result: rowCount !== undefined ? rowCount + ' 行' : result && astKind && astKind !== 'SelectStmt' ? '仅 SELECT' : undefined,
  }

  const run = async () => {
    if (busy || !dbPath || !sql.trim()) return
    setBusy(true)
    setLastRun(null)
    setError(null)
    setStage('tokens')
    try {
      const response = await api.pipeline(sql) as PipelineResult
      setLastRun({ sql, response })
    } catch (e) {
      setError(e instanceof ApiFailure ? e : new ApiFailure('CLIENT_ERROR', '无法运行 SQL 管线。'))
    } finally {
      setBusy(false)
    }
  }

  const selectStage = (index: number, focus = false) => {
    const { id } = stages[index]
    setStage(id)
    if (focus) document.getElementById('pipeline-tab-' + id)?.focus()
  }

  return (
    <div className="pipeline-view">
      <header className="pipe-page-head">
        <h1>SQL 执行流程实验</h1>
        <p>输入一条 SELECT，观察 MiniDB 如何把 SQL 转成可执行的查询。</p>
      </header>

      <form className="card pipe-input-card" aria-label="查询输入" onSubmit={(event) => {
        event.preventDefault()
        void run()
      }}>
        <div className="pipe-input-head">
          <label htmlFor="pipeline-sql-input">SQL 语句</label>
          {databaseName && <span className="pipe-context" title={dbPath ?? undefined}>
            <StudioIcon name="database" />查询当前数据库 <b className="mono">{databaseName}</b>
          </span>}
        </div>
        <div className="pipe-input-row">
          <div className="pipe-query">
            <StudioIcon name="terminal" />
            <input id="pipeline-sql-input" type="text" className="mono" value={sql}
              spellCheck={false} onChange={(event) => setSql(event.target.value)} />
          </div>
          <button type="submit" className="btn primary" disabled={busy || !dbPath || !sql.trim()}>
            <StudioIcon name="play" />{busy ? '运行中…' : '运行管线'}
          </button>
        </div>
        {!dbPath && <p className="pipe-input-note">
          {!ready ? '正在建立工作台会话。'
            : !connected ? 'MiniDB 服务未连接。'
            : <>尚未打开数据库。请先在<a href="#/start">开始页</a>打开数据库。</>}
        </p>}
        {lastRun && lastRun.sql !== sql && <p className="pipe-input-note" role="status">输入已更改。下方仍显示上一次运行的结果。</p>}
      </form>

      {error && <div className="pipe-error" role="alert"><ErrorBox error={error} /></div>}

      <ol className="pipe-stages" role="tablist" aria-label="SQL 管线阶段" onKeyDown={(event) => {
        let next: number
        switch (event.key) {
          case 'ArrowLeft':
          case 'ArrowUp': next = (stageIndex + stages.length - 1) % stages.length; break
          case 'ArrowRight':
          case 'ArrowDown': next = (stageIndex + 1) % stages.length; break
          case 'Home': next = 0; break
          case 'End': next = stages.length - 1; break
          default: return
        }
        event.preventDefault()
        selectStage(next, true)
      }}>
        {stages.map(({ id, label, caption }, index) => (
          <li key={id} role="presentation" className="pipe-stage">
            <button id={'pipeline-tab-' + id} type="button" className="pipe-stage-card" role="tab"
              aria-label={label} aria-controls={'pipeline-panel-' + id} aria-selected={stage === id}
              aria-current={stage === id ? 'step' : undefined} tabIndex={stage === id ? 0 : -1}
              onClick={() => selectStage(index)}>
              <span className="pipe-stage-number">{String(index + 1).padStart(2, '0')}</span>
              <span className="pipe-stage-copy"><strong>{label}</strong>
                <small>{caption}{stageMetadata[id] ? ' · ' + stageMetadata[id] : ''}</small>
              </span>
            </button>
            {index < stages.length - 1 && <StudioIcon name="arrow-right" className="pipe-stage-arrow" />}
          </li>
        ))}
      </ol>

      <section className="card pipe-detail" aria-label="阶段详情">
        <header className="pipe-detail-head">
          <div className="pipe-heading">
            <span className="pipe-heading-tile"><StudioIcon name={active.icon} /></span>
            <div><h2>{active.title}</h2><p>{active.detail}</p></div>
          </div>
          <span className="pipe-step-badge">步骤 {String(stageIndex + 1).padStart(2, '0')} / 04</span>
        </header>

        {stages.map(({ id }) => (
          <div key={id} id={'pipeline-panel-' + id} role="tabpanel" aria-labelledby={'pipeline-tab-' + id}
            hidden={stage !== id} tabIndex={0}>
            {stage === id && (result
              ? <PipelineDetail stage={stage} result={result} astKind={astKind} />
              : <p className="pipe-panel-empty" role="status">{busy ? 'MiniDB 正在运行查询管线…' : '运行管线后可在此查看阶段详情。'}</p>)}
          </div>
        ))}

        <footer className="pipe-detail-foot">
          <p><StudioIcon name="info" />{active.hint}</p>
          {stageIndex + 1 < stages.length && <button type="button" className="btn" disabled={!result}
            onClick={() => selectStage(stageIndex + 1, true)}>
            下一步：{stages[stageIndex + 1].label}<StudioIcon name="arrow-right" />
          </button>}
        </footer>
      </section>
    </div>
  )
}

function PipelineDetail({ stage, result, astKind }: { stage: StageId; result: PipelineResult; astKind?: string }) {
  if (stage === 'tokens') {
    if (!Array.isArray(result.tokens)) return <p className="pipe-panel-empty">响应未包含 Token。</p>
    return (
      <div className="pipe-table-wrap">
        <table className="pipe-table" aria-label="词法 Token">
          <thead><tr><th scope="col">#</th><th scope="col">类型</th><th scope="col">文本</th><th scope="col">位置</th></tr></thead>
          <tbody>
            {result.tokens.map(({ type, text, pos }, i) => (
              <tr key={i}><td>{i}</td><td><span className="pipe-token-type">{type}</span></td>
                <td>{text || <span className="muted">—</span>}</td><td>{pos}</td></tr>
            ))}
            {result.tokens.length === 0 && <tr><td colSpan={4} className="muted">没有 Token。</td></tr>}
          </tbody>
        </table>
      </div>
    )
  }

  if (stage === 'ast') {
    if (result.ast === undefined || result.ast === null) return <p className="pipe-panel-empty">响应未包含 AST。</p>
    return (
      <div className="pipe-detail-grid">
        <div className="pipe-pane">
          <h3>节点结构</h3>
          <div className="pipe-ast-scroll" tabIndex={0} aria-label="AST 节点结构">
            <AstTree branches={astBranches(result.ast)} />
          </div>
        </div>
        <div className="pipe-pane">
          <h3>AST JSON</h3>
          <AstJson ast={result.ast} />
        </div>
      </div>
    )
  }

  if (stage === 'plan') {
    if (astKind && astKind !== 'SelectStmt') return <p className="pipe-panel-empty">仅 SELECT 语句生成执行计划。</p>
    if (!result.planText) return <p className="pipe-panel-empty">响应未包含执行计划。</p>
    return <div className="pipe-plan-wrap"><pre className="pipe-plan" tabIndex={0}>{result.planText}</pre></div>
  }

  if (astKind && astKind !== 'SelectStmt') return <p className="pipe-panel-empty">此语句仅做解析，未执行。仅 SELECT 语句返回查询结果。</p>
  if (!Array.isArray(result.columns) || !Array.isArray(result.rows)) {
    return <p className="pipe-panel-empty">响应未包含查询结果。</p>
  }

  return (
    <div>
      <div className="pipe-result-summary">
        <span className="pipe-step-badge">{result.rowCount ?? result.rows.length} 行</span>
        {typeof result.elapsedMs === 'number' && <span className="muted">管线耗时 <span className="mono">{result.elapsedMs} ms</span></span>}
      </div>
      {result.truncated && <p className="notice-box pipe-result-notice">结果已按 1000 行上限截断。</p>}
      <div className="pipe-table-wrap">
        <table className="pipe-table" aria-label="管线执行结果">
          <thead><tr><th scope="col">#</th>{result.columns.map((column, i) => <th scope="col" key={i}>{column}</th>)}</tr></thead>
          <tbody>
            {result.rows.map((row, i) => (
              <tr key={i}><td>{i + 1}</td>{row.map((value, j) => <td key={j}>{value === null ? <span className="muted">NULL</span> : String(value)}</td>)}</tr>
            ))}
            {result.rows.length === 0 && <tr><td className="muted" colSpan={result.columns.length + 1}>查询完成，没有匹配的记录。</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  )
}
