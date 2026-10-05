import React, { useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'

type AnyMap = Record<string, unknown>

/** WAL 与恢复实验（计划书 §26/§27）：真实 crash → 真实 Recovery → 真实报告 */
export default function RecoveryLab() {
  const [result, setResult] = useState<AnyMap | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const [phase, setPhase] = useState<'before' | 'wal' | 'report' | 'after' | 'check'>('before')

  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      const r = await api.lab.recoveryRun()
      setResult(r)
      setPhase('before')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const report = (result?.recoveryReport as AnyMap[]) ?? []
  const rowsAfter = (result?.rowsAfterRecovery as AnyMap[]) ?? []
  const wal = (result?.walRecords as AnyMap[]) ?? []
  const checks = (result?.consistencyChecks as { name: string; pass: boolean }[]) ?? []

  return (
    <div>
      <h1 className="page-title">WAL 与恢复实验</h1>
      <p className="page-sub">
        独立实验库 + 真实断电模拟（db.crash()：缓冲池不刷盘、日志不 fsync 不截断）→ 重新打开触发真实 redo + undo 恢复
      </p>

      <div className="card">
        <div className="flow" style={{ marginBottom: 14 }}>
          {['创建实验库', '执行事务', '写入 WAL', '模拟进程终止', '重新打开', '执行 Recovery', 'redo', 'undo', '一致性检查'].map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <span className="arrow">→</span>}
              <span className="node">{s}</span>
            </React.Fragment>
          ))}
        </div>
        <button className="btn primary" disabled={busy} onClick={run}>
          {busy ? '实验运行中…' : '▶ 运行崩溃恢复实验'}
        </button>
        <p className="muted" style={{ fontSize: 12.5, marginTop: 8 }}>
          崩溃不是"正常关闭"模拟的：实验调用内核的 <span className="mono">crash()</span> —— 与真实进程终止相同的 abruptClose 语义。
        </p>
        {error && <ErrorBox error={error} />}
      </div>

      {result && (
        <>
          <div className="card">
            <div className="tabs">
              <button className={phase === 'before' ? 'active' : ''} onClick={() => setPhase('before')}>1. 崩溃前状态</button>
              <button className={phase === 'wal' ? 'active' : ''} onClick={() => setPhase('wal')}>2. WAL 记录</button>
              <button className={phase === 'report' ? 'active' : ''} onClick={() => setPhase('report')}>3. 恢复报告（redo/undo）</button>
              <button className={phase === 'after' ? 'active' : ''} onClick={() => setPhase('after')}>4. 恢复后数据</button>
              <button className={phase === 'check' ? 'active' : ''} onClick={() => setPhase('check')}>5. 一致性检查</button>
            </div>

            {phase === 'before' && (
              <div>
                <ul className="lab-steps">
                  {((result.beforeCrash as string[]) ?? []).map((s, i) => <li key={i}>{s}</li>)}
                </ul>
                <p className="muted" style={{ fontSize: 13 }}>
                  崩溃时缓冲池中有 {String(result.dirtyPagesAtCrash)} 个脏页未被刷盘 —— 它们的内容只有 WAL 里有。
                </p>
              </div>
            )}

            {phase === 'wal' && (
              <div>
                <p className="muted" style={{ fontSize: 13 }}>
                  崩溃前从 WAL 文件读出的真实记录（逻辑日志：{String(result.walBytes)} 字节，T1 的 COMMIT 后已 fsync {String(result.walBytesAfterCommit)} 字节）：
                </p>
                <div className="table-wrap">
                  <table className="grid">
                    <thead><tr><th>LSN</th><th>类型</th><th>事务</th><th>表</th><th>RID</th></tr></thead>
                    <tbody>
                      {wal.map((r, i) => (
                        <tr key={i}>
                          <td className="mono">{String(r.lsn)}</td>
                          <td><b>{String(r.type)}</b></td>
                          <td className="mono">T{String(r.txnId)}</td>
                          <td>{String(r.table)}</td>
                          <td className="mono">{String(r.rid ?? '—')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {phase === 'report' && (
              <div>
                <p className="muted" style={{ fontSize: 13 }}>
                  重新打开数据库时真实执行的恢复过程（条件幂等：镜像一致才操作）：
                  已提交事务 T{(result.committedTxns as string[])?.join('、T')}，未提交事务 T{(result.pendingTxns as string[])?.join('、T')}。
                </p>
                <div className="table-wrap">
                  <table className="grid">
                    <thead><tr><th>阶段</th><th>LSN</th><th>事务</th><th>动作</th><th>表.RID</th><th>应用?</th><th>说明</th></tr></thead>
                    <tbody>
                      {report.map((r, i) => (
                        <tr key={i}>
                          <td><b>{String(r.phase)}</b></td>
                          <td className="mono">{String(r.lsn ?? '—')}</td>
                          <td className="mono">T{String(r.txnId)}</td>
                          <td>{String(r.action)}</td>
                          <td className="mono">{String(r.table)}{r.rid ? `.${String(r.rid)}` : ''}</td>
                          <td>{r.applied ? '✔' : '—'}</td>
                          <td style={{ whiteSpace: 'normal' }}>{String(r.detail)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {phase === 'after' && (
              <div className="table-wrap">
                <table className="grid">
                  <thead><tr>{rowsAfter?.[0] && Object.keys(rowsAfter[0]).map((k) => <th key={k}>{k}</th>)}</tr></thead>
                  <tbody>
                    {rowsAfter.map((r, i) => (
                      <tr key={i}>{Object.values(r).map((v, j) => <td key={j}>{String(v)}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {phase === 'check' && (
              <div style={{ display: 'grid', gap: 8 }}>
                {checks.map((c, i) => (
                  <div key={i} className={c.pass ? 'notice-box' : 'error-box'}
                    style={c.pass ? { background: 'var(--primary-light)', color: 'var(--primary-dark)', borderColor: 'var(--border)' } : {}}>
                    {c.pass ? '✔' : '✘'} {c.name}
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}
