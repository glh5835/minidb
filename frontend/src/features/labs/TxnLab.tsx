import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox } from '../../components/ui'
import { useApp } from '../../stores/app'
import type { ApiError } from '../../types'
import './TxnLab.css'

const sides = ['A', 'B'] as const
type Side = typeof sides[number]
type Isolation = 'READ_COMMITTED' | 'REPEATABLE_READ'
type Command = 'BEGIN' | 'COMMIT' | 'ROLLBACK' | 'SQL'
type Template = 'rc' | 'rr' | 'lockwait' | 'deadlock'

interface SideState {
  active: boolean
  isolation: string
  txnId?: string
  heldLocks?: string[]
  pending: boolean
}

interface TxnState {
  sessions: Record<Side, SideState>
  locks: { key: string; holders: (string | number)[]; mode: string | null; waiting: (string | number)[] }[]
  liveRows: Record<string, unknown>[]
  columns: string[]
  liveRowsNote?: string
}

interface Step {
  session: Side
  action: Command
  sql?: string
  isolation?: Isolation
}

interface Reply {
  success?: boolean
  pending?: boolean
  error?: ApiError
  message?: string
  rowCount?: number
  rows?: unknown[][]
}

interface Ticket {
  step: Step
  result?: Reply
}

interface PollSide {
  state: string
  result?: Reply
  error?: string
}

interface LogEntry {
  id: number
  side: Side | '实验'
  detail: string
  kind: 'ok' | 'wait' | 'error' | 'info'
}

interface DeadlockEvent {
  victim: Side
  txnId?: string
  rollbackSent: boolean
  resolved: boolean
}

const templates: { id: Template; title: string; detail: string }[] = [
  { id: 'rc', title: 'READ COMMITTED', detail: 'A 读取旧余额，B 更新并提交，A 在同一事务中读到新余额。' },
  { id: 'rr', title: 'REPEATABLE READ', detail: 'A 重复读取同一行，B 的更新等待 A 提交，随后完成。' },
  { id: 'lockwait', title: '锁等待', detail: 'A 持有行锁，B 的更新挂起。观察等待图后，可手动提交 A。' },
  { id: 'deadlock', title: '死锁', detail: '交叉请求行锁，检测死锁后显式回滚牺牲者，再结束另一个事务。' },
]

const selectAccount = (id: number) => 'SELECT * FROM account WHERE id = ' + id
const updateAccount = (id: number, balance: number) => 'UPDATE account SET balance = ' + balance + ' WHERE id = ' + id
const sqlOptions = [selectAccount(1), selectAccount(2), updateAccount(1, 888), updateAccount(2, 999),
  updateAccount(1, 777), updateAccount(1, 555), updateAccount(1, 666), updateAccount(1, 1111)]
const otherSide = (side: Side): Side => side === 'A' ? 'B' : 'A'

function isDetectedDeadlock(reply: Reply) {
  return reply.error?.code === 'DEADLOCK' && reply.error.message.includes('检测到死锁')
}

function assertSuccess(reply: Reply) {
  if (reply.error || reply.success === false) {
    throw new ApiFailure(reply.error?.code ?? 'EXPERIMENT_FAILED', reply.error?.message ?? '实验操作失败。')
  }
}

export default function TxnLab() {
  const { confirm } = useApp()
  const [snapshot, setSnapshot] = useState<TxnState | null>(null)
  const current = useRef<TxnState | null>(null)
  const [loading, setLoading] = useState(true)
  const [readError, setReadError] = useState<ApiFailure | string | null>(null)
  const [actionError, setActionError] = useState<ApiFailure | string | null>(null)
  const [template, setTemplate] = useState<Template>('lockwait')
  const [busy, setBusy] = useState(false)
  const setupBusy = useRef(false)
  const [sending, setSending] = useState<Side[]>([])
  const activeSends = useRef(new Set<Side>())
  const tickets = useRef<Partial<Record<Side, Ticket>>>({})
  const [waiting, setWaiting] = useState<Side[]>([])
  const [isolation, setIsolation] = useState<Record<Side, Isolation>>({ A: 'REPEATABLE_READ', B: 'REPEATABLE_READ' })
  const [sql, setSql] = useState<Record<Side, string>>({ A: selectAccount(1), B: selectAccount(1) })
  const [log, setLog] = useState<LogEntry[]>([])
  const nextLogId = useRef(0)
  const [deadlock, setDeadlock] = useState<DeadlockEvent | null>(null)
  const latestDeadlock = useRef<DeadlockEvent | null>(null)
  const mounted = useRef(false)
  const queue = useRef<Promise<unknown>>(Promise.resolve())

  const serialize = useCallback(<T,>(operation: () => Promise<T>) => {
    const next = queue.current.then(operation)
    queue.current = next.catch(() => undefined)
    return next
  }, [])

  const writeLog = useCallback((side: LogEntry['side'], kind: LogEntry['kind'], detail: string) => {
    const entry = { id: nextLogId.current++, side, kind, detail }
    setLog((previous) => [entry, ...previous].slice(0, 60))
  }, [])

  const publishDeadlock = useCallback((event: DeadlockEvent | null) => {
    latestDeadlock.current = event
    setDeadlock(event)
  }, [])

  const record = useCallback((step: Step, reply: Reply) => {
    const label = step.sql ?? step.action
    if (reply.error || reply.success === false) {
      if (isDetectedDeadlock(reply)) {
        publishDeadlock({ victim: step.session, txnId: current.current?.sessions[step.session].txnId, rollbackSent: false, resolved: false })
        writeLog(step.session, 'error', label + ' → [DEADLOCK] 检测到循环等待，需显式回滚。')
      } else {
        writeLog(step.session, 'error', label + ' → ' + (reply.error ? '[' + reply.error.code + '] ' + reply.error.message : '操作失败'))
      }
      return
    }

    const event = latestDeadlock.current
    if (step.action === 'ROLLBACK' && event?.victim === step.session && !event.resolved) {
      publishDeadlock({ ...event, rollbackSent: true })
    }
    const result = reply.message ?? (reply.rowCount === undefined ? '完成' :
      reply.rowCount + ' 行' + (reply.rows?.[0] ? ' · ' + reply.rows[0].map(String).join(' / ') : ''))
    writeLog(step.session, 'ok', step.sql ? label + ' → ' + result : result)
  }, [publishDeadlock, writeLog])

  const publishSnapshot = useCallback((value: TxnState | null) => {
    current.current = value
    setSnapshot(value)
    const event = latestDeadlock.current
    if (!value || !event?.rollbackSent || event.resolved || value.sessions[event.victim].active) return
    const victimStillLocked = event.txnId && value.locks.some((lock) =>
      [...lock.holders, ...lock.waiting].some((id) => String(id) === event.txnId))
    if (!victimStillLocked && !value.sessions[otherSide(event.victim)].pending) {
      publishDeadlock({ ...event, resolved: true })
      writeLog('实验', 'ok', '已验证 Session ' + event.victim + ' 回滚并释放锁，循环等待已解除。')
    }
  }, [publishDeadlock, writeLog])

  const finishTicket = useCallback((ticket: Ticket, result: Reply) => {
    ticket.result = result
    delete tickets.current[ticket.step.session]
    setWaiting((previous) => previous.filter((side) => side !== ticket.step.session))
    record(ticket.step, result)
  }, [record])

  // txnPoll 会消费已完成的结果。按钮、模板和定时刷新都经同一个队列读取。
  const readCycle = useCallback(async () => {
    try {
      const polled = await api.lab.txnPoll() as Partial<Record<Side, PollSide>>
      for (const side of sides) {
        const ticket = tickets.current[side]
        const outcome = polled[side]
        if (!ticket || outcome?.state === 'waiting') continue
        const reply = outcome?.result ?? {
          success: false,
          error: { code: 'RESULT_UNAVAILABLE', message: outcome?.error ?? '无法读取挂起语句的完成结果。' },
        }
        finishTicket(ticket, reply)
      }
      publishSnapshot(await api.lab.txnState() as unknown as TxnState)
      setReadError(null)
    } catch (error) {
      if (error instanceof ApiFailure && error.code === 'EXPERIMENT_NOT_READY') {
        for (const side of sides) {
          const ticket = tickets.current[side]
          if (ticket) finishTicket(ticket, { success: false, error })
        }
        publishSnapshot(null)
        publishDeadlock(null)
        setReadError(null)
      } else {
        setReadError(error instanceof ApiFailure ? error : '读取事务实验状态失败。')
      }
    } finally {
      setLoading(false)
    }
  }, [finishTicket, publishDeadlock, publishSnapshot])

  useEffect(() => {
    mounted.current = true
    let cancelled = false
    let timer: number | undefined
    const tick = async () => {
      await serialize(async () => { if (!cancelled) await readCycle() })
      if (!cancelled) timer = window.setTimeout(tick, 600)
    }
    void tick()
    return () => {
      cancelled = true
      mounted.current = false
      window.clearTimeout(timer)
    }
  }, [readCycle, serialize])

  const execute = (step: Step) => serialize(async (): Promise<{ reply: Reply; ticket?: Ticket }> => {
    if (!mounted.current) throw new ApiFailure('EXPERIMENT_INTERRUPTED', '已离开事务实验。')
    if (tickets.current[step.session] || current.current?.sessions[step.session].pending) {
      throw new ApiFailure('EXPERIMENT_PENDING', 'Session ' + step.session + ' 还有挂起语句。')
    }
    const reply = await api.lab.txnExec(step) as Reply
    let ticket: Ticket | undefined
    if (reply.pending) {
      ticket = { step }
      tickets.current[step.session] = ticket
      setWaiting((previous) => [...previous, step.session])
      writeLog(step.session, 'wait', (step.sql ?? step.action) + ' → 语句挂起，请查看锁等待图。')
    } else {
      record(step, reply)
    }
    await readCycle()
    return { reply, ticket }
  })

  const drain = async ({ reply, ticket }: Awaited<ReturnType<typeof execute>>) => {
    if (!ticket) return reply
    const deadline = Date.now() + 8000
    while (!ticket.result && mounted.current && Date.now() < deadline) {
      await new Promise<void>((resolve) => window.setTimeout(resolve, 160))
      if (mounted.current && !ticket.result) await serialize(readCycle)
    }
    if (ticket.result) return ticket.result
    throw new ApiFailure('EXPERIMENT_PENDING', '挂起语句尚未返回结果。请查看当前锁状态。')
  }

  const manual = async (step: Step) => {
    if (setupBusy.current || activeSends.current.has(step.session)) return
    activeSends.current.add(step.session)
    setSending([...activeSends.current])
    setActionError(null)
    try {
      await execute(step)
    } catch (error) {
      writeLog(step.session, 'error', (step.sql ?? step.action) + ' → ' + (error instanceof Error ? error.message : '操作失败'))
    } finally {
      activeSends.current.delete(step.session)
      setSending([...activeSends.current])
    }
  }

  const runTemplate = async (kind: Template) => {
    const begin = async (session: Side, level: Isolation) => {
      setIsolation((previous) => ({ ...previous, [session]: level }))
      assertSuccess(await drain(await execute({ session, action: 'BEGIN', isolation: level })))
    }
    const runSql = (session: Side, statement: string) => {
      setSql((previous) => ({ ...previous, [session]: statement }))
      return execute({ session, action: 'SQL', sql: statement })
    }
    const requireSql = async (session: Side, statement: string) =>
      assertSuccess(await drain(await runSql(session, statement)))
    const finish = async (session: Side, action: 'COMMIT' | 'ROLLBACK') =>
      assertSuccess(await drain(await execute({ session, action })))
    const requirePending = async (session: Side, statement: string) => {
      const result = await runSql(session, statement)
      assertSuccess(result.reply)
      if (!result.ticket) throw new ApiFailure('EXPERIMENT_FAILED', '预期的等待语句已返回，请查看锁状态和日志。')
      const hasEdge = () => {
        const state = current.current
        const from = state?.sessions[session].txnId
        const to = state?.sessions[otherSide(session)].txnId
        return !!from && !!to && !!state?.locks.some((lock) =>
          lock.waiting.some((id) => String(id) === from) && lock.holders.some((id) => String(id) === to))
      }
      const deadline = Date.now() + 2500
      while (!hasEdge() && !result.ticket.result && mounted.current && Date.now() < deadline) {
        await new Promise<void>((resolve) => window.setTimeout(resolve, 120))
        if (mounted.current) await serialize(readCycle)
      }
      if (!hasEdge()) throw new ApiFailure('EXPERIMENT_FAILED', '快照中没有预期的等待关系。')
      return result
    }

    if (kind === 'rc') {
      await begin('A', 'READ_COMMITTED')
      await requireSql('A', selectAccount(1))
      await begin('B', 'READ_COMMITTED')
      await requireSql('B', updateAccount(1, 555))
      await finish('B', 'COMMIT')
      await requireSql('A', selectAccount(1))
      await finish('A', 'COMMIT')
    } else if (kind === 'rr') {
      await begin('A', 'REPEATABLE_READ')
      await requireSql('A', selectAccount(1))
      await begin('B', 'READ_COMMITTED')
      const blocked = await requirePending('B', updateAccount(1, 666))
      await requireSql('A', selectAccount(1))
      await finish('A', 'COMMIT')
      assertSuccess(await drain(blocked))
      await finish('B', 'COMMIT')
    } else if (kind === 'lockwait') {
      await begin('A', 'REPEATABLE_READ')
      await requireSql('A', updateAccount(1, 777))
      await begin('B', 'REPEATABLE_READ')
      await requirePending('B', updateAccount(1, 888))
      writeLog('实验', 'info', '锁等待已建立。观察等待图后，可手动提交或回滚 Session A。')
      return
    } else {
      await begin('A', 'REPEATABLE_READ')
      await requireSql('A', selectAccount(1))
      await begin('B', 'REPEATABLE_READ')
      await requireSql('B', selectAccount(2))
      const blocked = await requirePending('A', updateAccount(2, 999))
      const victim = await drain(await runSql('B', updateAccount(1, 1111)))
      if (!isDetectedDeadlock(victim)) {
        assertSuccess(victim)
        throw new ApiFailure('EXPERIMENT_FAILED', '没有收到内核死锁检测结果。')
      }
      await finish('B', 'ROLLBACK')
      assertSuccess(await drain(blocked))
      await finish('A', 'ROLLBACK')
      const state = current.current
      if (!state || state.sessions.A.active || state.sessions.B.active || state.locks.length) {
        throw new ApiFailure('EXPERIMENT_FAILED', '未能验证回滚后的无锁状态。')
      }
    }
    writeLog('实验', 'ok', '模板执行完成。')
  }

  const setup = async (kind?: Template) => {
    if (setupBusy.current || activeSends.current.size || Object.keys(tickets.current).length ||
      sides.some((side) => current.current?.sessions[side].pending)) return
    setupBusy.current = true
    setBusy(true)
    setActionError(null)
    try {
      if (current.current && !await confirm({
        title: '重置事务实验库',
        message: '重建实验库会清除当前会话和实验数据。工作库不受影响。',
        actionText: '确认重置',
      })) return
      await serialize(async () => {
        if (!mounted.current) throw new ApiFailure('EXPERIMENT_INTERRUPTED', '已离开事务实验。')
        const next = await api.lab.txnSetup() as unknown as TxnState
        tickets.current = {}
        setWaiting([])
        publishDeadlock(null)
        publishSnapshot(next)
        setReadError(null)
        setLog([])
        writeLog('实验', 'info', '实验环境已初始化。')
      })
      if (kind) await runTemplate(kind)
    } catch (error) {
      setActionError(error instanceof ApiFailure ? error : '执行事务实验失败。')
    } finally {
      setupBusy.current = false
      setBusy(false)
    }
  }

  const setupDisabled = loading || !!readError || busy || !!sending.length || !!waiting.length ||
    sides.some((side) => snapshot?.sessions[side].pending)

  return (
    <section className="txn-view">
      <header className="txn-heading">
        <div>
          <h1>事务与锁 <span className="txn-pill txn-lab"><StudioIcon name="flask" />独立实验库</span></h1>
          <p>对照两个事务会话，观察行锁等待和死锁解除过程。</p>
        </div>
        <button className="btn txn-reset" type="button" disabled={setupDisabled} onClick={() => setup()}>
          <StudioIcon name={snapshot ? 'refresh' : 'play'} />{snapshot ? '重置实验库' : '初始化实验库'}
        </button>
      </header>

      {loading && <p className="txn-state" role="status">正在读取事务实验状态…</p>}
      {readError && <div role="alert"><ErrorBox error={readError} />
        <button className="btn small" type="button" onClick={() => serialize(readCycle)}>重试读取</button></div>}
      {actionError && <div role="alert"><ErrorBox error={actionError} /></div>}

      <section className="card txn-template" aria-label="事务实验模板">
        <div className="txn-template-content">
          <div className="txn-template-list">
            <strong><StudioIcon name="flask" />实验模板</strong>
            {templates.map((entry) => (
              <button key={entry.id} className={'txn-template-choice' + (entry.id === 'deadlock' ? ' deadlock' : '')} type="button"
                aria-pressed={template === entry.id} disabled={busy} onClick={() => setTemplate(entry.id)}>{entry.title}</button>
            ))}
          </div>
          <p>{templates.find((entry) => entry.id === template)?.detail}</p>
        </div>
        <button className="btn" type="button" disabled={setupDisabled} onClick={() => setup(template)}>
          <StudioIcon name="play" />{busy ? '实验执行中…' : '运行模板'}
        </button>
      </section>

      <div className="txn-sessions">
        {sides.map((side) => (
          <SessionPanel key={side} side={side} snapshot={snapshot} isolation={isolation[side]} sql={sql[side]}
            busy={busy || sending.includes(side) || !!readError} pending={waiting.includes(side)}
            latest={log.find((entry) => entry.side === side)}
            onIsolation={(value) => setIsolation((previous) => ({ ...previous, [side]: value }))}
            onSql={(value) => setSql((previous) => ({ ...previous, [side]: value }))}
            onCommand={(action) => manual({
              session: side, action, sql: action === 'SQL' ? sql[side] : undefined,
              isolation: action === 'BEGIN' ? isolation[side] : undefined,
            })} />
        ))}
      </div>

      <LockGraph snapshot={snapshot} />

      <div className="txn-lower">
        <DeadlockPanel event={deadlock} />
        <LiveData snapshot={snapshot} />
        <section className="card">
          <header className="txn-card-head"><h2><StudioIcon name="terminal" />操作日志</h2></header>
          {log.length ? (
            <ul className="txn-log" role="log" aria-label="操作日志">
              {log.map((entry) => (
                <li key={entry.id} data-kind={entry.kind}>
                  <StudioIcon name={entry.kind === 'ok' ? 'check' : entry.kind === 'wait' ? 'clock' : 'info'} />
                  <span><strong>{entry.side}</strong> {entry.detail}</span>
                </li>
              ))}
            </ul>
          ) : <p className="txn-state">执行事务动作后显示。</p>}
        </section>
      </div>
    </section>
  )
}

function SessionPanel({ side, snapshot, isolation, sql, busy, pending, latest, onIsolation, onSql, onCommand }: {
  side: Side; snapshot: TxnState | null; isolation: Isolation; sql: string; busy: boolean; pending: boolean; latest?: LogEntry
  onIsolation: (value: Isolation) => void; onSql: (value: string) => void; onCommand: (command: Command) => void
}) {
  const state = snapshot?.sessions[side]
  const waiting = pending || !!state?.pending
  const disabled = busy || !state || waiting
  const held = (snapshot?.locks ?? []).filter((lock) => state?.txnId && lock.holders.some((id) => String(id) === state.txnId))
  const waits = (snapshot?.locks ?? []).filter((lock) => state?.txnId && lock.waiting.some((id) => String(id) === state.txnId))

  return (
    <section className={'card txn-session' + (waiting ? ' waiting' : '')} data-side={side} aria-label={'Session ' + side}>
      <header className="txn-card-head">
        <div className="txn-session-heading">
          <span className="txn-avatar">{side}</span>
          <div><h2>Session {side}</h2><p className="mono">{state?.txnId ? 'T' + state.txnId + ' · ' + state.isolation : '尚无活动事务'}</p></div>
        </div>
        <span className={'txn-pill txn-status' + (waiting ? ' amber' : '')}>
          {waiting ? waits.length ? '等待行锁' : '语句执行中' : state?.active ? '事务进行中' : '未开始'}
        </span>
      </header>
      <div className="txn-sql">
        <StudioIcon name="terminal" />
        <select aria-label="预置 SQL" value={sql} disabled={disabled} onChange={(event) => onSql(event.target.value)}>
          {sqlOptions.map((option) => <option key={option}>{option}</option>)}
        </select>
      </div>
      <div className="txn-session-actions">
        <div className="txn-command-set">
          <select aria-label="隔离级别" value={state?.active ? state.isolation : isolation} disabled={disabled || !!state?.active}
            onChange={(event) => onIsolation(event.target.value as Isolation)}>
            <option value="READ_COMMITTED">RC</option><option value="REPEATABLE_READ">RR</option>
          </select>
          <button className="btn mono" type="button" disabled={disabled || state.active} onClick={() => onCommand('BEGIN')}>BEGIN</button>
          <button className="btn primary mono" type="button" disabled={disabled || !state.active} onClick={() => onCommand('COMMIT')}>COMMIT</button>
          <button className="btn txn-rollback mono" type="button" disabled={disabled || !state.active} onClick={() => onCommand('ROLLBACK')}>ROLLBACK</button>
        </div>
        <button className="btn" type="button" disabled={disabled || !state.active} onClick={() => onCommand('SQL')}>
          <StudioIcon name={waiting ? 'clock' : 'play'} />{waiting ? '等待中' : '执行 SQL'}
        </button>
      </div>
      <div className="txn-session-foot">
        <span>{held.length || waits.length ? <>
          {held.map((lock) => <span key={lock.key}><strong>持有 {lock.mode} 锁</strong> · <code>{lock.key}</code> </span>)}
          {waits.map((lock) => <span key={lock.key}><strong>等待</strong> · <code>{lock.key}</code> </span>)}
        </> : '暂无持锁或等待'}</span>
        {latest && <span className="txn-last-action" title={latest.detail}>{latest.detail}</span>}
      </div>
    </section>
  )
}

function LockGraph({ snapshot }: { snapshot: TxnState | null }) {
  const locks = snapshot?.locks ?? []
  const edges = locks.flatMap((lock) => lock.waiting.flatMap((waiter) =>
    lock.holders.filter((holder) => String(waiter) !== String(holder)).map((holder) =>
      ({ from: String(waiter), to: String(holder), key: lock.key }))))
  const sideFor = (id: string) => sides.find((side) => snapshot?.sessions[side].txnId === id)
  const displayId = (id: string | number) => {
    const side = sideFor(String(id))
    return (side ? side + ' · ' : '') + 'T' + id
  }
  const graphNode = (id: string, label: string, waiter: boolean) => {
    const side = sideFor(id)
    return (
      <div className={'txn-edge-node' + (waiter ? ' waiter' : '')}>
        <span className="txn-avatar">{side ?? 'T'}</span>
        <div><strong>{side ? 'Session ' + side : '事务 T' + id}</strong><small>{label} · T{id}</small></div>
      </div>
    )
  }

  return (
    <section className="card txn-locks">
      <header className="txn-card-head">
        <h2><StudioIcon name="lock" />锁等待图</h2>
        {snapshot && <span className={'txn-pill' + (edges.length ? ' amber' : '')}>{edges.length} 条等待边</span>}
      </header>
      {snapshot ? (
        <div className="txn-lock-body">
          <div className="txn-edge-list">
            {edges.length ? edges.map(({ from, to, key }) => (
              <div className="txn-edge" key={from + '/' + to + '/' + key} data-from={from} data-to={to} data-lock={key}
                role="img" aria-label={displayId(from) + ' 等待 ' + displayId(to) + ' 持有的 ' + key}>
                {graphNode(from, '等待者', true)}
                <div className="txn-edge-line"><span>等待行锁</span>
                  <svg viewBox="0 0 100 9" aria-hidden="true"><path d="M2 4.5h95m-5-3 5 3-5 3" /></svg>
                </div>
                {graphNode(to, '持锁者', false)}
              </div>
            )) : <p className="txn-edge-empty">当前没有锁等待。</p>}
          </div>
          <div className="txn-lock-scroll">
            <table className="txn-lock-table" aria-label="行锁快照">
              <thead><tr><th scope="col">行锁键</th><th scope="col">模式</th><th scope="col">持有者</th><th scope="col">等待者</th></tr></thead>
              <tbody>{locks.length ? locks.map((lock) => (
                <tr key={lock.key}><td className="mono">{lock.key}</td><td className="mono">{lock.mode ?? '—'}</td>
                  <td>{lock.holders.map(displayId).join('、') || '—'}</td><td>{lock.waiting.map(displayId).join('、') || '—'}</td></tr>
              )) : <tr><td colSpan={4}>当前没有行锁。</td></tr>}</tbody>
            </table>
          </div>
        </div>
      ) : <p className="txn-state">初始化实验库后显示锁快照。</p>}
      <p className="txn-lock-note">箭头从等待者指向持锁者。</p>
    </section>
  )
}

function DeadlockPanel({ event }: { event: DeadlockEvent | null }) {
  return (
    <section className="card txn-replay">
      <header className="txn-card-head">
        <h2><StudioIcon name="activity" />{event ? '死锁处理' : '死锁模板预览'}</h2>
        <span className={'txn-pill ' + (event?.resolved ? '' : 'txn-preview')}>{event ? event.resolved ? '已解除' : '已检测' : '预期流程'}</span>
      </header>
      {event ? (
        <p className="txn-deadlock-note" role="status">
          <code>DEADLOCK</code> · Session {event.victim}
          {event.resolved ? ' 已回滚，快照确认等待已解除。' : event.rollbackSent ?
            ' 已执行 ROLLBACK，正在核对锁状态。' : ' 为牺牲者，请执行 ROLLBACK 释放行锁。'}
        </p>
      ) : (
        <>
          <div className="txn-stages">
            <div className="txn-stage"><strong>01 各自持锁</strong><span>A 持 id=1，B 持 id=2</span></div>
            <div className="txn-stage detect"><strong>02 循环等待</strong><span className="mono">A → B → A</span></div>
            <div className="txn-stage"><strong>03 解除等待</strong><span>回滚 B，A 恢复执行</span></div>
          </div>
          <p className="txn-deadlock-note">死锁模板在收到 <code>DEADLOCK</code> 后，显式回滚牺牲者。</p>
        </>
      )}
    </section>
  )
}

function LiveData({ snapshot }: { snapshot: TxnState | null }) {
  return (
    <section className="card">
      <header className="txn-card-head"><h2><StudioIcon name="grid" />实时数据</h2></header>
      {snapshot?.liveRows.length ? (
        <>
          <div className="txn-data-scroll" tabIndex={0} role="region" aria-label="账户表实时数据">
            <table className="txn-rows">
              <thead><tr>{snapshot.columns.map((column) => <th key={column} scope="col" className="mono">{column}</th>)}</tr></thead>
              <tbody>{snapshot.liveRows.map((row, index) => (
                <tr key={index}>{snapshot.columns.map((column) =>
                  <td key={column}>{String(row[column] ?? row[column.split('.').pop() ?? column] ?? '—')}</td>)}</tr>
              ))}</tbody>
            </table>
          </div>
          <p className="txn-data-note">共 {snapshot.liveRows.length} 行 · 无锁观测，可能含未提交值</p>
        </>
      ) : <p className="txn-state">初始化实验库后显示账户数据。</p>}
    </section>
  )
}
