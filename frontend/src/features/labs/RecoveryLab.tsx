import { useRef, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox } from '../../components/ui'
import { useApp } from '../../stores/app'
import type { WalRecord } from '../../types'
import './RecoveryLab.css'

type RecoveryWalRecord = Omit<WalRecord, 'hasBefore' | 'hasAfter'>

interface RecoveryEvent {
  lsn: string | null
  txnId: string
  phase: string
  action: string
  table: string
  rid: string | null
  applied: boolean
  detail: string
}

interface RecoveryCheck {
  name: string
  pass: boolean
}

interface RecoverySnapshot {
  beforeCrash?: string[]
  dirtyPagesAtCrash?: number
  walRecords?: RecoveryWalRecord[]
  recoveryReport?: RecoveryEvent[]
  committedTxns?: string[]
  pendingTxns?: string[]
  rowsAfterRecovery?: Record<string, unknown>[]
  consistencyChecks?: RecoveryCheck[]
}

const stages = [
  { id: 'before', label: '崩溃前状态', caption: '提交与未提交事务' },
  { id: 'wal', label: 'WAL 记录', caption: '按 LSN 查看日志' },
  { id: 'report', label: '恢复报告', caption: '分析、重放、撤销' },
  { id: 'after', label: '恢复后数据', caption: '查看 account 表' },
  { id: 'check', label: '一致性检查', caption: '核对恢复结果' },
] as const

type StageId = (typeof stages)[number]['id']

function orderedWal(records: RecoveryWalRecord[]): RecoveryWalRecord[] {
  return [...records].sort((a, b) => {
    const left = BigInt(a.lsn)
    const right = BigInt(b.lsn)
    return left < right ? -1 : left > right ? 1 : 0
  })
}

function keyEvents(report: RecoveryEvent[]): RecoveryEvent[] {
  const analyze = report.filter(({ phase }) => phase === 'ANALYZE')
  const redo = report.filter(({ phase, action }) => phase === 'REDO' && ['INSERT', 'UPDATE', 'DELETE'].includes(action))
  const undo = report.filter(({ phase }) => phase === 'UNDO')
  const highlighted = new Set([...analyze.slice(0, 2), ...redo.slice(0, 1), ...redo.slice(-1), ...undo.slice(0, 2)])
  return highlighted.size ? report.filter((entry) => highlighted.has(entry)) : report.slice(0, 6)
}

function txnLabel(id: string): string {
  return id ? 'T' + id : '—'
}

/** 崩溃和重新打开都在独立实验库中执行。 */
export default function RecoveryLab() {
  const { ready, connected } = useApp()
  const [result, setResult] = useState<RecoverySnapshot | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const [stage, setStage] = useState<StageId>('before')
  const [fullReport, setFullReport] = useState(false)
  const activeRun = useRef(false)
  const stageIndex = stages.findIndex(({ id }) => id === stage)
  const checkCount = result?.consistencyChecks?.length

  const run = async () => {
    if (activeRun.current || !ready || !connected) return
    activeRun.current = true
    setBusy(true)
    setError(null)
    setResult(null)
    setStage('before')
    setFullReport(false)
    try {
      setResult(await api.lab.recoveryRun() as RecoverySnapshot)
    } catch (e) {
      setError(e instanceof ApiFailure ? e : new ApiFailure('CLIENT_ERROR', '无法运行崩溃恢复实验。'))
    } finally {
      activeRun.current = false
      setBusy(false)
    }
  }

  const selectStage = (index: number, focus = false) => {
    const { id } = stages[index]
    setStage(id)
    if (focus) document.getElementById('recovery-tab-' + id)?.focus()
  }

  return (
    <div className="recovery-view">
      <header className="rv-page-head">
        <div className="rv-title">
          <h1>WAL 与恢复实验</h1>
          <span className="rv-pill rv-lab-badge"><StudioIcon name="flask" />独立实验库</span>
        </div>
        <p>从崩溃前的事务和写前日志出发，观察数据库重新打开时如何恢复。</p>
      </header>

      <section className="card rv-scenario" aria-label="实验场景">
        <div className="rv-scenario-head">
          <div>
            <h2>实验场景</h2>
            <p>每次运行重建独立实验库，在 <code className="mono">account</code> 表制造两种事务状态，
              调用内核 <code className="mono">crash()</code> 模拟异常退出，再重新打开实验库。</p>
          </div>
          <button type="button" className="btn primary" disabled={busy || !ready || !connected} onClick={() => void run()}>
            <StudioIcon name="play" />{busy ? '实验运行中…' : '运行崩溃恢复实验'}
          </button>
        </div>
        {result?.beforeCrash?.length ? <BeforeTransactions entries={result.beforeCrash} /> : (
          <p className="rv-scenario-note" role="status">
            {!ready ? '正在连接 MiniDB 服务。' : !connected ? 'MiniDB 服务未连接。'
              : busy ? '正在重建实验库、模拟崩溃并读取恢复结果…'
              : result ? '响应未提供崩溃前事务记录。' : '运行后显示此次提交与未提交事务。'}
          </p>
        )}
      </section>

      {error && <div className="rv-error" role="alert"><ErrorBox error={error} /></div>}

      <ol className="rv-rail" role="tablist" aria-label="崩溃恢复实验阶段" onKeyDown={(event) => {
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
          <li key={id} role="presentation">
            <button id={'recovery-tab-' + id} type="button" className="rv-stage" role="tab" aria-label={label}
              aria-controls={'recovery-panel-' + id} aria-selected={stage === id}
              aria-current={stage === id ? 'step' : undefined} tabIndex={stage === id ? 0 : -1}
              onClick={() => selectStage(index)}>
              <span className="rv-step">{String(index + 1).padStart(2, '0')}</span>
              <span className="rv-stage-copy">
                <strong>{label}</strong><small>{id === 'check' && checkCount ? '核对 ' + checkCount + ' 项断言' : caption}</small>
              </span>
            </button>
            {index + 1 < stages.length && <StudioIcon name="arrow-right" className="rv-stage-arrow" />}
          </li>
        ))}
      </ol>

      {stages.map(({ id, label }) => (
        <div key={id} id={'recovery-panel-' + id} role="tabpanel" aria-labelledby={'recovery-tab-' + id}
          className="rv-panel" hidden={stage !== id} tabIndex={0}>
          {stage === id && (result ? (
            <RecoveryDetail stage={stage} result={result} fullReport={fullReport} setFullReport={setFullReport} />
          ) : (
            <section className="card rv-empty">
              <h2>{label}</h2>
              <p>{busy ? '正在等待本次恢复结果。' : error ? '本次实验未返回可查看的结果。' : '运行实验后可在此查看阶段详情。'}</p>
            </section>
          ))}
          {stage === id && result && stageIndex + 1 < stages.length && (
            <div className="rv-panel-next">
              <button type="button" className="btn" onClick={() => selectStage(stageIndex + 1, true)}>
                下一步：{stages[stageIndex + 1].label}<StudioIcon name="arrow-right" />
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

function BeforeTransactions({ entries }: { entries: string[] }) {
  return (
    <ul className="rv-txns">
      {entries.map((entry, i) => {
        const parsed = /^(T\d+)（([^）]+)）[：:]\s*(.*)$/.exec(entry)
        if (!parsed) return <li className="rv-txn" key={i}>{entry}</li>
        const [, txn, status, description] = parsed
        const pending = status === '未提交'
        return (
          <li key={i} className={'rv-txn' + (pending ? ' pending' : '')}>
            <span className={'rv-pill' + (pending ? ' amber' : '')}><span className="mono">{txn}</span>{status === '提交' ? '已提交' : status}</span>
            <span>{description}</span>
          </li>
        )
      })}
    </ul>
  )
}

function RecoveryDetail({ stage, result, fullReport, setFullReport }: {
  stage: StageId
  result: RecoverySnapshot
  fullReport: boolean
  setFullReport: (value: boolean) => void
}) {
  if (stage === 'before') {
    return (
      <section className="card rv-detail-card">
        <header className="rv-detail-head"><div><h2>崩溃前状态</h2><p>以上事务记录来自本次实验在异常退出前保存的快照。</p></div></header>
        <p className="rv-detail-note">
          {typeof result.dirtyPagesAtCrash === 'number'
            ? <>崩溃时缓冲池中有 <strong className="mono">{result.dirtyPagesAtCrash}</strong> 个尚未写回的脏页。</>
            : '响应未提供崩溃时的脏页数。'}
        </p>
      </section>
    )
  }

  if (stage === 'wal') {
    const records = orderedWal(result.walRecords ?? [])
    return (
      <section className="card rv-detail-card">
        <header className="rv-detail-head">
          <div><h2>崩溃前 WAL 日志</h2><p>异常退出前从 WAL 读取的记录，按 LSN 升序显示。</p></div>
          {records.length > 0 && <span className="rv-pill">{records.length} 条</span>}
        </header>
        <WalTable records={records} />
      </section>
    )
  }

  if (stage === 'after') {
    return (
      <section className="card rv-detail-card">
        <OutcomeRows rows={result.rowsAfterRecovery} />
      </section>
    )
  }

  if (stage === 'check') {
    return (
      <section className="card rv-detail-card">
        <OutcomeChecks checks={result.consistencyChecks ?? []} />
      </section>
    )
  }

  const report = result.recoveryReport ?? []
  const preview = keyEvents(report)
  const hasMore = report.length > preview.length
  const entries = fullReport ? report : preview
  const committed = result.committedTxns
  const pending = result.pendingTxns
  return (
    <div className="rv-report-content">
      <WalPreview records={orderedWal(result.walRecords ?? [])} pending={pending ?? []} />
      <div className="rv-grid">
        <section className="card rv-report" aria-label="恢复报告">
          <header className="rv-detail-head">
            <div><h2>恢复报告</h2><p>重开实验库后，恢复器先识别事务状态，再按日志处理变更。</p></div>
            {hasMore && <button className="btn rv-report-toggle" type="button" aria-expanded={fullReport} aria-controls="recovery-report-rows"
              onClick={() => setFullReport(!fullReport)}>{fullReport ? '仅看关键记录' : '查看全部 ' + report.length + ' 条'}</button>}
          </header>
          <div className="rv-phases">
            <div className="rv-phase">
              <b>ANALYZE</b>
              <p>{committed && pending
                ? <>已提交：{committed.map(txnLabel).join('、') || '无'} · 未提交：{pending.map(txnLabel).join('、') || '无'}</>
                : '识别提交与未提交的事务'}</p>
            </div>
            <div className="rv-phase"><b>REDO</b><p>按 LSN 顺序检查并重放变更</p></div>
            <div className="rv-phase"><b>UNDO</b><p>逆序撤销未提交事务的变更</p></div>
          </div>
          <ReportTable entries={entries} />
        </section>
        <aside className="card rv-outcome" aria-label="恢复结果摘要">
          <OutcomeRows rows={result.rowsAfterRecovery} limit={2} />
          <OutcomeChecks checks={result.consistencyChecks ?? []} limit={4} />
        </aside>
      </div>
    </div>
  )
}

function WalPreview({ records, pending }: { records: RecoveryWalRecord[]; pending: string[] }) {
  const preview = records.slice(0, 7)
  return (
    <section className="card rv-wal-preview" aria-label="WAL 日志摘要">
      <header>
        <h2><StudioIcon name="file" />崩溃前 WAL 日志</h2>
        {records.length > 0 && <span>按 LSN 升序 · {preview.length < records.length
          ? '前 ' + preview.length + ' / ' + records.length + ' 条' : records.length + ' 条'}</span>}
      </header>
      {preview.length === 0 ? <p className="rv-subtle">响应未包含 WAL 记录。</p> : (
        <ol className="rv-wal-records">
          {preview.map((entry, i) => (
            <li key={i} className={entry.type === 'COMMIT' ? 'rv-wal-commit'
              : pending.includes(entry.txnId) ? 'rv-wal-pending' : undefined}
              title={[entry.table, entry.rid].filter(Boolean).join(' · ') || undefined}>
              <small>LSN {entry.lsn} · {txnLabel(entry.txnId)}</small>
              <strong>{entry.type}</strong>{entry.rid && <span className="mono">{entry.rid}</span>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

function WalTable({ records }: { records: RecoveryWalRecord[] }) {
  if (!records.length) return <p className="rv-detail-note">响应未包含 WAL 记录。</p>
  return (
    <div className="rv-table-wrap" tabIndex={0}>
      <table className="rv-table" aria-label="崩溃前 WAL 日志">
        <thead><tr><th scope="col">LSN</th><th scope="col">类型</th><th scope="col">事务</th><th scope="col">表</th><th scope="col">RID</th></tr></thead>
        <tbody>
          {records.map((entry, i) => (
            <tr key={i}><td className="mono">{entry.lsn}</td><td className="mono">{entry.type}</td>
              <td className="mono">{txnLabel(entry.txnId)}</td><td>{entry.table || '—'}</td><td className="mono">{entry.rid ?? '—'}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ReportTable({ entries }: { entries: RecoveryEvent[] }) {
  if (!entries.length) return <p className="rv-detail-note">响应未包含恢复动作。</p>
  return (
    <div className="rv-table-wrap" tabIndex={0}>
      <table className="rv-table" aria-label="恢复报告明细">
        <thead><tr><th scope="col">阶段</th><th scope="col">LSN</th><th scope="col">事务</th><th scope="col">动作</th>
          <th scope="col">表 · RID</th><th scope="col">应用状态</th><th scope="col">说明</th></tr></thead>
        <tbody id="recovery-report-rows">
          {entries.map((entry, i) => {
            const phaseClass = entry.phase === 'REDO' ? ' redo' : entry.phase === 'UNDO' ? ' undo' : ''
            const status = entry.applied === true ? (entry.phase === 'ANALYZE' ? '已识别' : '已应用')
              : entry.applied === false ? '跳过' : '未提供'
            return (
              <tr key={i}>
                <td><span className={'rv-phase-tag' + phaseClass}>{entry.phase}</span></td>
                <td className="mono">{entry.lsn ?? '—'}</td><td className="mono">{txnLabel(entry.txnId)}</td>
                <td className="mono">{entry.action}</td><td className="mono">{[entry.table, entry.rid].filter(Boolean).join(' · ') || '—'}</td>
                <td className={entry.applied ? 'rv-status-done' : 'rv-status-skip'}>{status}</td><td className="rv-explanation">{entry.detail || '—'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function OutcomeRows({ rows, limit = rows?.length ?? 0 }: { rows?: Record<string, unknown>[]; limit?: number }) {
  const columns = Object.keys(rows?.[0] ?? {})
  return (
    <>
      <header className="rv-detail-head">
        <div><h2>恢复后数据</h2><p className="mono">account</p></div>
        {rows && <span className="rv-pill">{rows.length > limit ? '前 ' + limit + ' / ' + rows.length + ' 行' : rows.length + ' 行'}</span>}
      </header>
      {!rows ? <p className="rv-detail-note">响应未包含恢复后数据。</p> : rows.length === 0 ? <p className="rv-detail-note">恢复后表中没有记录。</p> : (
        <div className="rv-table-wrap" tabIndex={0}>
          <table className="rv-table" aria-label="恢复后数据">
            <thead><tr>{columns.map((column) => <th scope="col" className="mono" key={column}>{column}</th>)}</tr></thead>
            <tbody>{rows.slice(0, limit).map((row, i) => (
              <tr key={i}>{columns.map((column) => {
                const value = row[column]
                return <td className={typeof value === 'number' ? 'mono' : undefined} key={column}>{value === null ? 'NULL' : String(value ?? '—')}</td>
              })}</tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </>
  )
}

function OutcomeChecks({ checks, limit = checks.length }: { checks: RecoveryCheck[]; limit?: number }) {
  const passed = checks.filter(({ pass }) => pass === true).length
  return (
    <div className="rv-checks">
      <header className="rv-detail-head">
        <h2>一致性检查</h2>
        {checks.length > 0 && <span className={'rv-pill rv-check-count' + (passed < checks.length ? ' failed' : '')}>{passed} / {checks.length} 通过</span>}
      </header>
      {checks.length === 0 ? <p className="rv-subtle">响应未包含一致性检查结果。</p> : (
        <ul className="rv-check-list">
          {checks.slice(0, limit).map(({ name, pass }, i) => (
            <li key={i} className={pass ? undefined : 'failed'}>
              <StudioIcon name={pass ? 'check' : 'x'} /><span className="rv-check-name">{name}</span>
              <span className="rv-check-status">{pass ? '通过' : '失败'}</span>
            </li>
          ))}
        </ul>
      )}
      {checks.length > limit && <p className="rv-subtle">显示前 {limit} 项，完整结果见「一致性检查」。</p>}
    </div>
  )
}
