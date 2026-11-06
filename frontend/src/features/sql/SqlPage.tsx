import { useCallback, useEffect, useRef, useState } from 'react'
import CodeMirror, { EditorView, type ReactCodeMirrorRef, type ViewUpdate } from '@uiw/react-codemirror'
import { sql, StandardSQL } from '@codemirror/lang-sql'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox, PlanTree, exportCsv, useHistory } from '../../components/ui'
import { useApp } from '../../stores/app'
import type { PlanResult, StmtResult } from '../../types'
import './SqlPage.css'

const DRAFT_KEY = 'minidb-studio-sql-draft'
const editorExtensions = [sql(), EditorView.contentAttributes.of({ 'aria-label': 'SQL 编辑器' })]
const outputTabs = [
  { id: 'result', label: '结果' },
  { id: 'message', label: '消息' },
  { id: 'plan', label: '执行计划' },
  { id: 'history', label: '历史' },
] as const

type OutputTab = (typeof outputTabs)[number]['id']
type Operation = 'run' | 'plan' | 'transaction'

function planQuery(draft: string): string {
  const statements = StandardSQL.language.parser.parse(draft).topNode.getChildren('Statement')
  for (const statement of statements) {
    const text = draft.slice(statement.from, statement.to)
    if (/^SELECT\b/i.test(text)) return text
  }
  return draft
}

/** SQL 编辑、执行结果、计划、历史和当前会话的事务。 */
export default function SqlPage() {
  const { session, refresh, notify, confirm } = useApp()
  const [sqlText, setSqlText] = useState(() => localStorage.getItem(DRAFT_KEY) ?? 'SELECT * FROM student;')
  const [results, setResults] = useState<StmtResult[]>([])
  const [plan, setPlan] = useState<PlanResult | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [pending, setPending] = useState<Operation | null>(null)
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [tab, setTab] = useState<OutputTab>('result')
  const [cursor, setCursor] = useState({ line: 1, column: 1, hasSelection: false })
  const { hist, add } = useHistory()
  const editorRef = useRef<ReactCodeMirrorRef>(null)
  const busy = useRef(false)
  const hasDb = Boolean(session?.hasDb)
  const txnActive = Boolean(session?.txn.active)

  useEffect(() => {
    localStorage.setItem(DRAFT_KEY, sqlText)
  }, [sqlText])

  const updateCursor = useCallback(({ state, docChanged, selectionSet }: ViewUpdate) => {
    if (!docChanged && !selectionSet) return
    const { from, to, head } = state.selection.main
    const line = state.doc.lineAt(head)
    setCursor({
      line: line.number,
      column: head - line.from + 1,
      hasSelection: from !== to && Boolean(state.sliceDoc(from, to).trim()),
    })
  }, [])

  const selectedSql = () => {
    const state = editorRef.current?.view?.state
    if (!state) return ''
    const { from, to } = state.selection.main
    return state.sliceDoc(from, to)
  }

  const perform = useCallback(async (operation: Operation, action: () => Promise<void>) => {
    if (busy.current) return
    busy.current = true
    setPending(operation)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e instanceof ApiFailure ? e : new ApiFailure('CLIENT_ERROR', '工作台操作失败。'))
    } finally {
      busy.current = false
      setPending(null)
    }
  }, [])

  const run = useCallback((text: string) => {
    if (!hasDb) {
      notify('尚未打开数据库，请先在开始页创建或打开。', 'error')
      return
    }
    void perform('run', async () => {
      const start = performance.now()
      const response = await api.executeSql(text)
      setResults(response.statements)
      setElapsed(Math.round(performance.now() - start))
      setTab(response.statements.some((statement) => !statement.success) ? 'message' : 'result')
      for (const statement of response.statements) {
        add({
          sql: statement.sql, time: Date.now(), db: session?.dbPath ?? '',
          ok: statement.success, elapsedMs: statement.elapsedMs,
        })
      }
      await refresh()
    })
  }, [add, hasDb, notify, perform, refresh, session?.dbPath])

  // Ctrl+Enter 执行整份草稿。只运行选中内容时使用独立按钮。
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key !== 'Enter' || event.isComposing) return
      event.preventDefault()
      event.stopPropagation()
      if (!event.repeat && !busy.current && !document.querySelector('dialog[open]')) run(sqlText)
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [run, sqlText])

  const showPlan = () => {
    void perform('plan', async () => {
      setPlan(null)
      setTab('plan')
      const selection = selectedSql()
      setPlan(await api.plan(selection.trim() ? selection : planQuery(sqlText)))
    })
  }

  const changeTransaction = (action: 'begin' | 'commit' | 'rollback') => {
    void perform('transaction', async () => {
      if (action === 'rollback') {
        if (!await confirm({
          title: '回滚事务',
          message: '回滚当前事务会撤销自事务开始以来未提交的更改。',
          actionText: '确认回滚',
        })) return
        await api.rollback()
      } else if (action === 'begin') {
        await api.begin()
      } else {
        await api.commit()
      }
      await refresh()
    })
  }

  return (
    <div className="sql-view">
      <header className="sql-page-head">
        <h1>SQL 工作台</h1>
        <p>编写和运行 SQL，查看查询结果、执行计划与历史记录。</p>
      </header>

      {!hasDb && <div className="notice-box">尚未打开数据库。请先<a href="#/start">打开数据库</a>。</div>}

      <section className="card sql-editor-card" aria-label="SQL 编辑区">
        <div className="sql-command-bar">
          <div className="sql-command-group" role="group" aria-label="查询操作">
            <button className="btn primary" type="button" disabled={!hasDb || pending !== null}
              onClick={() => run(sqlText)} title="运行整份草稿" aria-keyshortcuts="Control+Enter Meta+Enter">
              <StudioIcon name="play" />{pending === 'run' ? '运行中…' : '运行'}<kbd>Ctrl+Enter</kbd>
            </button>
            <button className="btn" type="button" disabled={!hasDb || pending !== null || !cursor.hasSelection}
              title="选中 SQL 文本后可用" onClick={() => {
                const selection = selectedSql()
                if (selection.trim()) run(selection)
              }}>
              <StudioIcon name="scan" />运行选中
            </button>
            <button className="btn" type="button" disabled={!hasDb || pending !== null} onClick={showPlan}>
              <StudioIcon name="tree" />执行计划
            </button>
          </div>

          <div className="sql-transaction-group" role="group" aria-label="事务操作">
            <span className="sql-transaction-label"><StudioIcon name="lock" />事务</span>
            <button className="btn" type="button" disabled={!hasDb || txnActive || pending !== null}
              onClick={() => changeTransaction('begin')}>开始事务</button>
            <button className="btn" type="button" disabled={!hasDb || !txnActive || pending !== null}
              onClick={() => changeTransaction('commit')}>提交</button>
            <button className={txnActive ? 'btn danger' : 'btn'} type="button" disabled={!hasDb || !txnActive || pending !== null}
              onClick={() => changeTransaction('rollback')}>回滚</button>
          </div>
        </div>

        <div className="sql-code-editor">
          <CodeMirror
            ref={editorRef}
            value={sqlText}
            height="clamp(148px, 23vh, 202px)"
            extensions={editorExtensions}
            basicSetup={{ lineNumbers: true, bracketMatching: true, autocompletion: true, highlightActiveLine: true }}
            onUpdate={updateCursor}
            onChange={setSqlText}
          />
        </div>

        <div className="sql-editor-status">
          <span><StudioIcon name="terminal" />SQL 编辑器</span>
          <span className="mono">行 {cursor.line} · 列 {cursor.column}</span>
        </div>
      </section>

      {txnActive && (
        <div className="notice-box sql-transaction-notice">
          显式事务进行中（{session?.txn.isolation}）。请先提交或回滚，再执行结构变更（DDL）。
        </div>
      )}
      {error && <div role="alert"><ErrorBox error={error} /></div>}

      <section className="card sql-result-card" aria-label="运行结果">
        <div className="sql-result-tabs">
          <div className="sql-tab-group" role="tablist" aria-label="输出视图" onKeyDown={(event) => {
            const index = outputTabs.findIndex(({ id }) => id === tab)
            let next: number
            switch (event.key) {
              case 'ArrowLeft': next = (index + outputTabs.length - 1) % outputTabs.length; break
              case 'ArrowRight': next = (index + 1) % outputTabs.length; break
              case 'Home': next = 0; break
              case 'End': next = outputTabs.length - 1; break
              default: return
            }
            event.preventDefault()
            const nextTab = outputTabs[next].id
            setTab(nextTab)
            event.currentTarget.querySelector<HTMLButtonElement>('#sql-tab-' + nextTab)?.focus()
          }}>
            {outputTabs.map(({ id, label }) => (
              <button key={id} id={'sql-tab-' + id} className="sql-tab" type="button" role="tab"
                aria-selected={tab === id} aria-controls={'sql-panel-' + id}
                tabIndex={tab === id ? 0 : -1} onClick={() => setTab(id)}>
                {label}
                {id === 'result' && results.length > 0 && <span className="sql-pill" aria-hidden="true">{results.length}</span>}
              </button>
            ))}
          </div>
          {elapsed !== null && <span className="sql-duration">总耗时 <span className="mono">{elapsed} ms</span></span>}
        </div>

        <div id="sql-panel-result" role="tabpanel" aria-labelledby="sql-tab-result" hidden={tab !== 'result'} tabIndex={0}>
          {tab === 'result' && <ResultPanel results={results} />}
        </div>
        <div id="sql-panel-message" role="tabpanel" aria-labelledby="sql-tab-message" hidden={tab !== 'message'} tabIndex={0}>
          {tab === 'message' && <MessagePanel results={results} />}
        </div>
        <div id="sql-panel-plan" role="tabpanel" aria-labelledby="sql-tab-plan" hidden={tab !== 'plan'} tabIndex={0}>
          {tab === 'plan' && (
            <div className="sql-panel-content">
              {plan ? <PlanTree tree={plan.tree} text={plan.text} />
                : <div className="muted">{pending === 'plan' ? '正在获取执行计划…' : '点击工具栏中的「执行计划」，查看 SELECT 语句的优化器计划。'}</div>}
            </div>
          )}
        </div>
        <div id="sql-panel-history" role="tabpanel" aria-labelledby="sql-tab-history" hidden={tab !== 'history'} tabIndex={0}>
          {tab === 'history' && (
            hist.length === 0 ? <p className="sql-panel-content muted">暂无历史记录。</p> : (
              <div className="sql-grid-wrap">
                <table className="sql-result-grid" aria-label="SQL 执行历史">
                  <thead><tr><th scope="col">时间</th><th scope="col">SQL</th><th scope="col">状态</th><th scope="col">耗时</th><th scope="col">操作</th></tr></thead>
                  <tbody>
                    {hist.map((item, i) => (
                      <tr key={i}>
                        <td className="mono">{new Date(item.time).toLocaleTimeString()}</td>
                        <td className="mono sql-history-statement" title={item.sql}>{item.sql}</td>
                        <td>{item.ok ? '成功' : '失败'}</td>
                        <td className="mono">{item.elapsedMs} ms</td>
                        <td><button className="btn" type="button" onClick={() => {
                          setSqlText(item.sql)
                          setTab('result')
                          editorRef.current?.view?.focus()
                        }}>放回编辑器</button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          )}
        </div>
      </section>
    </div>
  )
}

function ResultPanel({ results }: { results: StmtResult[] }) {
  if (results.length === 0) return <p className="sql-panel-content muted">执行 SQL 后在此查看结果。</p>
  const withRows = results.filter((result) => result.columns && result.rows)
  if (withRows.length === 0) return <p className="sql-panel-content muted">本次执行没有行结果。请查看「消息」标签。</p>

  return (
    <div>
      {withRows.map((result) => {
        const columns = result.columns!
        const rows = result.rows!
        const number = result.index + 1
        return (
          <div className="sql-statement-result" key={result.index}>
            <div className="sql-result-head">
              <div className="sql-result-summary" title={result.sql}>
                <strong>语句 {number}</strong>
                {result.success && <span className="sql-pill"><StudioIcon name="check" />执行成功</span>}
                <span className="sql-duration">{result.rowCount ?? rows.length} 行 · <span className="mono">{result.elapsedMs} ms</span></span>
              </div>
              <button className="btn" type="button" onClick={() => exportCsv(columns, rows, 'query-' + number + '.csv')}>
                <StudioIcon name="download" />导出 CSV
              </button>
            </div>
            {result.truncated && <div className="notice-box sql-result-notice">{result.notice ?? '结果已截断。'}</div>}
            <div className="sql-grid-wrap">
              <table className="sql-result-grid" aria-label={'语句 ' + number + ' 的查询结果'}>
                <thead><tr><th scope="col" className="sql-row-number">#</th>{columns.map((column, i) => <th scope="col" key={i}>{column}</th>)}</tr></thead>
                <tbody>
                  {rows.map((row, i) => (
                    <tr key={i}>
                      <td className="sql-row-number mono">{i + 1}</td>
                      {row.map((value, j) => (
                        <td key={j} className={typeof value === 'number' || (typeof value === 'string' && /^-?\d{15,}$/.test(value)) ? 'mono' : undefined}>
                          {value === null ? <span className="muted">NULL</span> : String(value)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )
      })}
    </div>
  )
}

function MessagePanel({ results }: { results: StmtResult[] }) {
  if (results.length === 0) return <p className="sql-panel-content muted">执行 SQL 后在此查看执行消息。</p>
  return (
    <div className="sql-panel-content sql-messages">
      {results.map((result) => (
        result.success ? (
          <div key={result.index} className="sql-success-message">
            语句 {result.index + 1} 成功（{result.elapsedMs} ms）{result.message ? '：' + result.message : ''}
            {result.rowCount !== undefined ? ' · ' + result.rowCount + ' 行' : ''}
          </div>
        ) : (
          <div key={result.index} role="alert">
            <ErrorBox error={{
              code: result.error?.code ?? 'ERROR',
              message: '语句 ' + (result.index + 1) + ' 失败：' + (result.error?.message ?? ''),
              line: result.error?.line,
              column: result.error?.column,
            }} />
          </div>
        )
      ))}
    </div>
  )
}
