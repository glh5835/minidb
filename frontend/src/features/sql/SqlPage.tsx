import React, { useCallback, useEffect, useRef, useState } from 'react'
import CodeMirror from '@uiw/react-codemirror'
import { sql } from '@codemirror/lang-sql'
import { api, ApiFailure } from '../../api/client'
import { useApp } from '../../stores/app'
import { Empty, ErrorBox, PlanTree, exportCsv, useHistory } from '../../components/ui'
import { nav } from '../../router'
import type { StmtResult } from '../../types'

const DRAFT_KEY = 'minidb-studio-sql-draft'

/** SQL 工作台（计划书 §9-§14）：CodeMirror 编辑器 + 结果/消息/计划/历史 + 事务控制 */
export default function SqlPage() {
  const { session, refresh, notify } = useApp()
  const [sqlText, setSqlText] = useState(() => localStorage.getItem(DRAFT_KEY) ?? 'SELECT * FROM student;')
  const [results, setResults] = useState<StmtResult[]>([])
  const [plan, setPlan] = useState<{ text: string; tree: import('../../types').PlanNode[] } | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [running, setRunning] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [tab, setTab] = useState<'result' | 'message' | 'plan' | 'history'>('result')
  const { hist, add } = useHistory()
  const editorRef = useRef<{ view: { state: { sliceDoc: (from?: number, to?: number) => string } } } | null>(null)
  const stopRef = useRef(false)

  useEffect(() => {
    localStorage.setItem(DRAFT_KEY, sqlText)
  }, [sqlText])

  const selectedSql = useCallback((): string | null => {
    const view = editorRef.current?.view
    if (!view) return null
    const sel = view.state.sliceDoc(
      (view as unknown as { state: { selection: { main: { from: number; to: number } } } }).state.selection.main.from,
      (view as unknown as { state: { selection: { main: { from: number; to: number } } } }).state.selection.main.to)
    return sel.trim() ? sel : null
  }, [])

  const run = useCallback(async (onlyIndex?: number) => {
    if (!session?.hasDb) {
      notify('尚未打开数据库，请先在开始页创建或打开。', 'error')
      return
    }
    setRunning(true)
    stopRef.current = false
    setError(null)
    const t0 = performance.now()
    try {
      const r = await api.executeSql(sqlText, onlyIndex)
      setResults(r.statements)
      setElapsed(Math.round(performance.now() - t0))
      const last = r.statements[r.statements.length - 1]
      if (last?.error) setTab('message')
      else setTab('result')
      // 记录历史（计划书 §12：SQL / 时间 / 数据库 / 成败 / 耗时）
      r.statements.forEach((st) => {
        add({
          sql: st.sql, time: Date.now(), db: session.dbPath ?? '',
          ok: !!st.success, elapsedMs: st.elapsedMs,
        })
      })
      await refresh()
      if (last?.truncated) notify(last.notice ?? '结果已截断', 'error')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setRunning(false)
    }
  }, [sqlText, session, notify, refresh, add])

  // Ctrl + Enter 执行（计划书 §40）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault()
        run()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [run])

  const showPlan = async () => {
    setError(null)
    try {
      const sqlOne = selectedSql() ?? sqlText.split(';').find((s) => s.trim().toUpperCase().startsWith('SELECT')) ?? sqlText
      setPlan(await api.plan(sqlOne))
      setTab('plan')
    } catch (e) {
      if (e instanceof ApiFailure) {
        setError(e)
        setTab('plan')
      }
    }
  }

  const doTxn = async (action: 'begin' | 'commit' | 'rollback') => {
    setError(null)
    try {
      if (action === 'begin') await api.begin()
      else if (action === 'commit') await api.commit()
      else await api.rollback()
      await refresh()
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    }
  }

  const txnActive = session?.txn?.active

  return (
    <div>
      {!session?.hasDb && (
        <div className="notice-box">尚未打开数据库。请先 <a href="#/start" style={{ color: 'var(--primary)' }}>打开数据库</a>。</div>
      )}
      <div className="sql-editor">
        <CodeMirror
          ref={editorRef as never}
          value={sqlText}
          height="200px"
          extensions={[sql()]}
          basicSetup={{ lineNumbers: true, bracketMatching: true, autocompletion: true, highlightActiveLine: true }}
          onChange={setSqlText}
        />
      </div>

      <div className="sql-toolbar">
        <button className="btn primary" disabled={running || !session?.hasDb} onClick={() => run()}>
          ▶ 执行 <kbd>Ctrl+Enter</kbd>
        </button>
        <button className="btn" disabled={running || !session?.hasDb}
          onClick={() => {
            const sel = selectedSql()
            if (!sel) {
              notify('请先在编辑器中选中要执行的语句。', 'error')
              return
            }
            run()
          }}>
          ▶ 执行选中
        </button>
        <button className="btn" disabled={!running} onClick={() => { stopRef.current = true; notify('将在当前语句结束后停止。') }}>
          ■ 停止
        </button>
        <button className="btn" disabled={!session?.hasDb} onClick={showPlan}>🌳 执行计划</button>
        <div className="grow" style={{ flex: 1 }} />
        <span className="row">
          <button className="btn small" disabled={txnActive || !session?.hasDb} onClick={() => doTxn('begin')}>
            开始事务
          </button>
          <button className="btn small primary" disabled={!txnActive} onClick={() => doTxn('commit')}>提交</button>
          <button className="btn small danger" disabled={!txnActive} onClick={() => doTxn('rollback')}>回滚</button>
          <span className="badge">{txnActive ? `● ACTIVE · ${session?.txn.isolation}` : '自动提交'}</span>
        </span>
      </div>

      {txnActive && (
        <div className="notice-box">
          显式事务进行中（{session?.txn.isolation}）。结构变更（DDL）暂不支持事务回滚，请提交或回滚后再执行 DDL。
        </div>
      )}
      {error && <ErrorBox error={error} />}

      <div className="tabs">
        <button className={tab === 'result' ? 'active' : ''} onClick={() => setTab('result')}>
          结果 {results.length > 0 && `(${results.length})`}
        </button>
        <button className={tab === 'message' ? 'active' : ''} onClick={() => setTab('message')}>消息</button>
        <button className={tab === 'plan' ? 'active' : ''} onClick={() => setTab('plan')}>执行计划</button>
        <button className={tab === 'history' ? 'active' : ''} onClick={() => setTab('history')}>历史</button>
        {elapsed > 0 && <span className="muted" style={{ marginLeft: 'auto', padding: '7px 12px' }}>共 {elapsed} ms</span>}
      </div>

      {tab === 'result' && <ResultPanel results={results} />}
      {tab === 'message' && <MessagePanel results={results} />}
      {tab === 'plan' && (plan
        ? <PlanTree tree={plan.tree} text={plan.text} />
        : <div className="muted">点击"执行计划"查看 SELECT 语句的真实计划树（来自 MiniDB 优化器）。</div>)}
      {tab === 'history' && (
        hist.length === 0 ? <div className="muted">暂无历史记录。</div> : (
          <div className="table-wrap">
            <table className="grid">
              <thead><tr><th>时间</th><th>SQL</th><th>状态</th><th>耗时</th><th></th></tr></thead>
              <tbody>
                {hist.map((h, i) => (
                  <tr key={i}>
                    <td className="mono">{new Date(h.time).toLocaleTimeString()}</td>
                    <td className="mono" style={{ maxWidth: 480, overflow: 'hidden', textOverflow: 'ellipsis' }}>{h.sql}</td>
                    <td>{h.ok ? '✅' : '❌'}</td>
                    <td>{h.elapsedMs} ms</td>
                    <td><button className="btn small" onClick={() => { setSqlText(h.sql); setTab('result') }}>放回编辑器</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </div>
  )
}

function ResultPanel({ results }: { results: StmtResult[] }) {
  if (results.length === 0) return <div className="muted">执行 SQL 后在此查看结果。</div>
  const withRows = results.filter((r) => r.columns && r.rows)
  if (withRows.length === 0) {
    return <div className="muted">本次执行没有行结果。请查看"消息"标签。</div>
  }
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      {withRows.map((r) => (
        <div key={r.index}>
          <div className="row" style={{ marginBottom: 6 }}>
            <b>语句 {r.index + 1}</b>
            <span className="muted">{r.rowCount} 行 · {r.elapsedMs} ms</span>
            <div style={{ flex: 1 }} />
            <button className="btn small" onClick={() => exportCsv(r.columns!, r.rows!, `query-${r.index + 1}.csv`)}>
              导出 CSV
            </button>
          </div>
          {r.truncated && <div className="notice-box">{r.notice}</div>}
          <div className="table-wrap">
            <table className="grid">
              <thead><tr>{r.columns!.map((c) => <th key={c}>{c}</th>)}</tr></thead>
              <tbody>
                {r.rows!.map((row, i) => (
                  <tr key={i}>
                    {row.map((v, j) => (
                      <td key={j} className={typeof v === 'string' && /^-?\d{15,}$/.test(v) ? 'mono' : ''}>
                        {v === null ? <span className="muted">NULL</span> : String(v)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}
    </div>
  )
}

function MessagePanel({ results }: { results: StmtResult[] }) {
  if (results.length === 0) return <div className="muted">执行 SQL 后在此查看执行消息。</div>
  return (
    <div style={{ display: 'grid', gap: 8 }}>
      {results.map((r) => (
        <div key={r.index}>
          {r.success ? (
            <div className="notice-box" style={{ background: 'var(--primary-light)', color: 'var(--primary-dark)', borderColor: 'var(--border)' }}>
              语句 {r.index + 1} 成功（{r.elapsedMs} ms）{r.message ? `：${r.message}` : ''}
              {r.rowCount !== undefined ? ` · ${r.rowCount} 行` : ''}
            </div>
          ) : (
            <div>
              <ErrorBox error={{ code: r.error?.code ?? 'ERROR', message: `语句 ${r.index + 1} 失败：${r.error?.message ?? ''}`, line: r.error?.line, column: r.error?.column }} />
              {results.length > r.index + 1 && <div className="muted">脚本已停止执行（后续语句未运行）。</div>}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
