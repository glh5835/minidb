import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'

type AnyMap = Record<string, unknown>

interface SideState {
  active: boolean
  isolation: string
  txnId?: string
  heldLocks?: string[]
  pending?: boolean
}

interface TxnStateData {
  sessions: { A: SideState; B: SideState }
  locks: { key: string; holders: string[]; mode: string | null; waiting: string[] }[]
  liveRows: Record<string, unknown>[]
  columns: string[]
  liveRowsNote?: string
}

const EXPERIMENTS = [
  {
    id: 'rc', title: '实验 1 · READ COMMITTED：读不欠账',
    steps: [
      'A：BEGIN（READ_COMMITTED）',
      'B：BEGIN（READ_COMMITTED）',
      'A：UPDATE account SET balance = 555 WHERE id = 1（A 持有行 X 锁，未提交）',
      'B：SELECT id=1 → 读到的是已提交的旧值（RC 读不加锁/读完即放，依赖内核行为）',
      'A：COMMIT → B 再读 → 新值可见',
    ],
    script: [
      { session: 'A', action: 'BEGIN', isolation: 'READ_COMMITTED' },
      { session: 'B', action: 'BEGIN', isolation: 'READ_COMMITTED' },
      { session: 'A', action: 'SQL', sql: 'UPDATE account SET balance = 555 WHERE id = 1' },
      { session: 'B', action: 'SQL', sql: 'SELECT * FROM account WHERE id = 1' },
      { session: 'A', action: 'COMMIT' },
    ],
  },
  {
    id: 'rr', title: '实验 2 · REPEATABLE READ：可重复读',
    steps: [
      'A：BEGIN（REPEATABLE_READ），SELECT id=1 记住值',
      'B：BEGIN，UPDATE id=1 并 COMMIT',
      'A：再次 SELECT id=1 → 仍是旧值（RR 读持有锁直到提交，防止不可重复读）',
    ],
    script: [
      { session: 'A', action: 'BEGIN', isolation: 'REPEATABLE_READ' },
      { session: 'A', action: 'SQL', sql: 'SELECT * FROM account WHERE id = 1' },
      { session: 'B', action: 'BEGIN', isolation: 'READ_COMMITTED' },
      { session: 'B', action: 'SQL', sql: 'UPDATE account SET balance = 666 WHERE id = 1' },
      { session: 'B', action: 'COMMIT' },
      { session: 'A', action: 'SQL', sql: 'SELECT * FROM account WHERE id = 1' },
    ],
  },
  {
    id: 'lockwait', title: '实验 3 · 锁等待',
    steps: [
      'A：BEGIN，UPDATE id=1（持 X 锁）',
      'B：BEGIN，UPDATE id=1 → 等待（锁等待图出现 B→A 边）',
      '观察锁等待图 → A：COMMIT → B 获得锁继续执行',
    ],
    script: [
      { session: 'A', action: 'BEGIN', isolation: 'REPEATABLE_READ' },
      { session: 'A', action: 'SQL', sql: 'UPDATE account SET balance = 777 WHERE id = 1' },
      { session: 'B', action: 'BEGIN', isolation: 'REPEATABLE_READ' },
      { session: 'B', action: 'SQL', sql: 'UPDATE account SET balance = 888 WHERE id = 1' },
      { session: 'A', action: 'COMMIT' },
    ],
  },
  {
    id: 'deadlock', title: '实验 4 · 死锁（等待图检测）',
    steps: [
      'A：BEGIN（RR），SELECT id=1 → 持有该行锁（RR 读锁持有到提交）',
      'B：BEGIN（RR），SELECT id=2 → 持有该行锁',
      'A：UPDATE id=2 → 等待 B',
      'B：UPDATE id=1 → 等待图成环 → 内核死锁检测：B 为牺牲者，报 DEADLOCK',
      'B：ROLLBACK（释放锁）→ A 的 UPDATE 完成 → A：ROLLBACK',
    ],
    script: [
      { session: 'A', action: 'BEGIN', isolation: 'REPEATABLE_READ' },
      { session: 'A', action: 'SQL', sql: 'SELECT * FROM account WHERE id = 1' },
      { session: 'B', action: 'BEGIN', isolation: 'REPEATABLE_READ' },
      { session: 'B', action: 'SQL', sql: 'SELECT * FROM account WHERE id = 2' },
      { session: 'A', action: 'SQL', sql: 'UPDATE account SET balance = 999 WHERE id = 2' },
      { session: 'B', action: 'SQL', sql: 'UPDATE account SET balance = 1111 WHERE id = 1' },
    ],
  },
]

/** 双会话事务实验（计划书 §24/§25）：两个真实会话 + 真实 LockManager 等待图 */
export default function TxnLab() {
  const [state, setState] = useState<TxnStateData | null>(null)
  const [pending, setPending] = useState<Record<string, { state: string; result?: AnyMap }>>({})
  type LogKind = 'ok' | 'err' | 'info'
  interface LogEntry { side: string; text: string; kind: LogKind }
  const [log, setLog] = useState<LogEntry[]>([])
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const timerRef = useRef<number | null>(null)

  const refreshState = useCallback(async () => {
    try {
      setState(await api.lab.txnState() as unknown as TxnStateData)
    } catch (e) {
      if (e instanceof ApiFailure && !e.code.startsWith('EXPERIMENT')) setError(e)
    }
  }, [])

  // 轮询挂起操作 + 状态刷新
  useEffect(() => {
    timerRef.current = window.setInterval(async () => {
      try {
        const p = await api.lab.txnPoll()
        setPending(p)
        await refreshState()
      } catch { /* 未初始化 */ }
    }, 1200)
    return () => { if (timerRef.current) clearInterval(timerRef.current) }
  }, [refreshState])

  const setup = async () => {
    setBusy(true)
    setError(null)
    try {
      setState(await api.lab.txnSetup() as unknown as TxnStateData)
      setPending({})
      setLog([{ side: 'LAB', text: '实验环境就绪：account 表 50 行 + id 唯一索引；会话 A/B 未开始事务', kind: 'info' as LogKind }])
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const exec = async (side: string, action: string, sql?: string, isolation?: string) => {
    setError(null)
    try {
      const r = await api.lab.txnExec({ session: side, action, sql, isolation })
      const ok = r.success !== false && !r.pending
      const text = r.pending
        ? `${sql ?? action} → 等待锁中（可观察锁等待图）`
        : r.error
          ? `${sql ?? action} → [${(r.error as AnyMap).code}] ${(r.error as AnyMap).message}`
          : `${sql ?? action} → ${(r.message as string) ?? '完成'}`
      const kind: LogKind = r.error ? 'err' : 'ok'
      setLog((l) => [{ side, text, kind }, ...l].slice(0, 60))
      if (r.pending) setLog((l) => [{ side, text: `语句在后台真实执行：${sql}`, kind: 'info' as LogKind }, ...l].slice(0, 60))
      await refreshState()
      void ok
    } catch (e) {
      if (e instanceof ApiFailure) {
        setLog((l) => [{ side, text: `${action}${sql ? ` ${sql}` : ''} → [${e.code}] ${e.message}`, kind: 'err' as LogKind }, ...l].slice(0, 60))
      }
    }
  }

  const runExperiment = async (exp: typeof EXPERIMENTS[number]) => {
    setBusy(true)
    try {
      await setup()
      setLog((l) => [{ side: 'LAB', text: `开始执行：${exp.title}`, kind: 'info' as LogKind }, ...l])
      for (const step of exp.script) {
        await exec(step.session, step.action, step.sql, step.isolation)
        // 有挂起时等待收敛（实验 3 的 B 语句要等 A 提交后才完成）
        for (let i = 0; i < 80; i++) {
          const p = await api.lab.txnPoll()
          const st = p[step.session]?.state
          if (st === 'idle' || st === 'done' || st === 'error') {
            const res = p[step.session]?.result
            if (res && (res.success === false || res.error)) {
              setLog((l) => [{ side: step.session, text: `${String(step.sql ?? step.action)} → [${(res.error as AnyMap)?.code}] ${(res.error as AnyMap)?.message}`, kind: 'err' as LogKind }, ...l])
            }
            break
          }
          await new Promise((r) => setTimeout(r, 120))
        }
        await new Promise((r) => setTimeout(r, 350))
      }
    } finally {
      setBusy(false)
    }
  }

  const sidePanel = (side: 'A' | 'B') => {
    const s = state?.sessions?.[side]
    const p = pending[side]
    return (
      <div className="session-panel">
        <h3>
          Session {side}
          {s?.active ? <span className="badge">ACTIVE · {s.isolation}</span> : <span className="badge">自动提交</span>}
          {(p?.state === 'waiting' || s?.pending) && <span className="badge lab">等待锁…</span>}
        </h3>
        <div className="muted" style={{ fontSize: 12.5 }}>
          事务 ID：{s?.txnId ?? '—'}
          {s?.heldLocks && s.heldLocks.length > 0 && (
            <div style={{ marginTop: 4 }}>
              持有锁：{s.heldLocks.map((k) => <span key={k} className="lock-badge mono">{k}</span>)}
            </div>
          )}
        </div>
        <div className="row" style={{ marginTop: 10 }}>
          <button className="btn small" disabled={busy || s?.active} onClick={() => exec(side, 'BEGIN')}>BEGIN</button>
          <button className="btn small primary" disabled={busy || !s?.active} onClick={() => exec(side, 'COMMIT')}>COMMIT</button>
          <button className="btn small danger" disabled={busy || !s?.active} onClick={() => exec(side, 'ROLLBACK')}>ROLLBACK</button>
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          <select id={`sel-${side}`} defaultValue="SELECT * FROM account WHERE id = 1" className="mono" style={{ flex: 1, fontSize: 12 }}>
            <option value="SELECT * FROM account WHERE id = 1">SELECT * FROM account WHERE id = 1</option>
            <option value="SELECT * FROM account WHERE id = 2">SELECT * FROM account WHERE id = 2</option>
            <option value="UPDATE account SET balance = 888 WHERE id = 1">UPDATE id=1</option>
            <option value="UPDATE account SET balance = 999 WHERE id = 2">UPDATE id=2</option>
          </select>
          <button className="btn small" disabled={busy} onClick={() => {
            const v = (document.getElementById(`sel-${side}`) as HTMLSelectElement).value
            exec(side, 'SQL', v)
          }}>执行</button>
        </div>
      </div>
    )
  }

  // 锁等待图：等待者 → 持有者
  const waitEdges = (state?.locks ?? []).flatMap((l) =>
    l.waiting.map((w) => ({ from: w, to: l.holders.filter((h) => h !== w), key: l.key, mode: l.mode })))

  return (
    <div>
      <h1 className="page-title">事务与锁实验</h1>
      <p className="page-sub">
        独立实验库 · 两个真实事务会话 · 严格 2PL + 等待图死锁检测（全部真实内核行为）
      </p>
      {error && <ErrorBox error={error} />}

      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2>实验环境</h2>
          <button className="btn small primary" disabled={busy} onClick={setup}>初始化 / 重置</button>
        </div>
        <div className="row" style={{ alignItems: 'flex-start' }}>
          {sidePanel('A')}
          {sidePanel('B')}
        </div>
      </div>

      <div className="card">
        <h2>锁等待图（真实 LockManager 快照）</h2>
        {(state?.locks ?? []).length === 0 ? (
          <div className="muted">当前无任何锁。执行事务语句后这里会显示持锁/等待关系。</div>
        ) : (
          <div>
            {(state?.locks ?? []).map((l) => (
              <div key={l.key} className="mono" style={{ fontSize: 13, padding: '4px 0' }}>
                <span className="lock-badge">{l.key}</span> 持有：{l.holders.map((h) => `T${h}`).join(',') || '∅'}（{l.mode}）
                {l.waiting.length > 0 && <span style={{ color: '#d97706' }}> ← 等待：{l.waiting.map((w) => `T${w}`).join(',')}</span>}
              </div>
            ))}
            {waitEdges.some((e) => e.to.length > 0) && (
              <pre className="code" style={{ marginTop: 8 }}>{`等待图：
${waitEdges.filter((e) => e.to.length > 0).map((e) => `  T${e.from} ──waits──▶ T${e.to.join(', T')}  （${e.key}）`).join('\n')}`}</pre>
            )}
            {log.some((l) => l.kind === 'err' && l.text.includes('DEADLOCK')) && (
              <div className="error-box">检测到循环等待 · 牺牲者已回滚（查看日志中的 DEADLOCK）</div>
            )}
          </div>
        )}
      </div>

      <div className="card">
        <h2>实验模板（一键运行真实步骤序列）</h2>
        <div style={{ display: 'grid', gap: 10 }}>
          {EXPERIMENTS.map((exp) => (
            <div key={exp.id} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: '10px 14px' }}>
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <b>{exp.title}</b>
                <button className="btn small primary" disabled={busy} onClick={() => runExperiment(exp)}>运行</button>
              </div>
              <ol className="lab-steps muted" style={{ fontSize: 12.5, margin: '6px 0 0' }}>
                {exp.steps.map((s, i) => <li key={i}>{s}</li>)}
              </ol>
            </div>
          ))}
        </div>
      </div>

      <div className="row" style={{ alignItems: 'flex-start' }}>
        <div className="card" style={{ flex: 1, minWidth: 0 }}>
          <h2>实时数据 {state?.liveRowsNote ? <span className="muted" style={{ fontSize: 12 }}>({state.liveRowsNote})</span> : null}</h2>
          {(state?.liveRows ?? []).length === 0 ? <div className="muted">初始化实验环境后显示。</div> : (
            <div className="table-wrap" style={{ maxHeight: 260, overflow: 'auto' }}>
              <table className="grid">
                <thead><tr>{state?.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
                <tbody>
                  {state?.liveRows.map((r, i) => (
                    <tr key={i}>{state.columns.map((c) => <td key={c}>{String(r[c])}</td>)}</tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        <div className="card" style={{ flex: 1, minWidth: 0 }}>
          <h2>操作日志</h2>
          <pre className="code" style={{ maxHeight: 260 }}>
            {log.length === 0 ? <span className="muted">（无）</span> :
              log.map((l, i) => `${l.kind === 'err' ? '❌' : l.kind === 'info' ? 'ℹ️' : '✅'} [${l.side}] ${l.text}`).join('\n')}
          </pre>
        </div>
      </div>
    </div>
  )
}
