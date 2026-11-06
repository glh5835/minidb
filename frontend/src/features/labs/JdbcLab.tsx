import { useRef, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox } from '../../components/ui'
import { useApp } from '../../stores/app'
import type { JdbcLabResult } from '../../types'
import './JdbcLab.css'

const stepLabels = {
  connect: { title: '建立连接', call: 'MiniDbDriver.connect(…)' },
  create: { title: '创建 user 表', call: 'Statement.execute("CREATE TABLE user …")' },
  insert: { title: '绑定参数并插入', call: 'PreparedStatement.executeUpdate()' },
  query: { title: '查询并遍历', call: 'Statement.executeQuery() → ResultSet.next()' },
  rollback: { title: '手动事务 · 回滚', call: 'setAutoCommit(false) → UPDATE → rollback()' },
  commit: { title: '手动事务 · 提交', call: 'PreparedStatement.executeUpdate() → commit()' },
  delete: { title: '恢复自动提交并删除', call: 'setAutoCommit(true) → DELETE' },
}

function formatScore(score: number) {
  return Number.isInteger(score) ? score.toFixed(1) : String(score)
}

/** 真实 MiniDbDriver 在独立实验库中执行 JDBC 流程。 */
export default function JdbcLab() {
  const { ready, connected } = useApp()
  const [result, setResult] = useState<JdbcLabResult | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const activeRun = useRef(false)

  const run = async () => {
    if (activeRun.current || !ready) return
    activeRun.current = true
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      setResult(await api.lab.jdbcRun())
    } catch (e) {
      setError(e instanceof ApiFailure ? e : new ApiFailure('CLIENT_ERROR', '无法运行 JDBC 实验。'))
    } finally {
      activeRun.current = false
      setBusy(false)
    }
  }

  return (
    <div className="jdbc-view">
      <header className="jdbc-intro">
        <div className="jdbc-title">
          <h1>JDBC 实验</h1>
          <span className="jdbc-pill jdbc-lab"><StudioIcon name="flask" />独立实验库</span>
        </div>
        <p>通过标准 JDBC 接口连接 MiniDB，跟踪建表、写入、查询和事务处理。</p>
      </header>

      <section className="card jdbc-connection" aria-labelledby="jdbc-connection-title">
        <div>
          <h2 id="jdbc-connection-title">驱动与连接</h2>
          <div className="jdbc-connection-details">
            <span className="jdbc-driver"><StudioIcon name="plug" /><code>MiniDbDriver</code></span>
            <code>jdbc:minidb:jdbc.exp.db</code>
          </div>
        </div>
        <button className="btn primary" type="button" disabled={busy || !ready} onClick={() => void run()}>
          <StudioIcon name="play" />{busy ? 'JDBC 流程运行中…' : '运行 JDBC 实验'}
        </button>
      </section>

      {error && <div role="alert"><ErrorBox error={error} /></div>}
      {result ? <JdbcResults result={result} /> : !error && (
        <p className="card jdbc-empty" role="status">
          {busy ? '正在运行 JDBC 流程并读取最终数据…' : !ready ? '正在连接 MiniDB 服务。'
            : !connected ? 'MiniDB 服务未连接，恢复连接后可以运行实验。'
              : '运行实验后可查看执行步骤、事务回滚和最终数据。'}
        </p>
      )}
    </div>
  )
}

function JdbcResults({ result }: { result: JdbcLabResult }) {
  const { steps, rollback, rows, log, code } = result
  const rollbackIndex = steps?.findIndex(({ id }) => id === 'rollback') ?? -1
  const transactionSteps = steps?.filter(({ id }) => id === 'rollback' || id === 'commit' || id === 'delete')
  const restored = rollback && rollback.before !== rollback.pending && rollback.before === rollback.restored

  return (
    <>
      <div className="jdbc-workspace">
        <section className="card jdbc-trace" aria-labelledby="jdbc-trace-title">
          <header className="jdbc-section-heading">
            <div><h2 id="jdbc-trace-title">执行步骤</h2><p>按 JDBC 调用顺序排列</p></div>
            {steps?.length > 0 && <span className="jdbc-pill">本次运行 · {steps.length} 步</span>}
          </header>
          {steps?.length ? (
            <ol className="jdbc-steps" aria-label="JDBC 执行步骤">
              {steps.map(({ id, summary }, index) => {
                const label = stepLabels[id]
                return (
                  <li key={id} className={id === 'rollback' ? 'jdbc-step-focus' : undefined}>
                    <span className="jdbc-step-number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                    <div className="jdbc-step-copy"><h3>{label.title}</h3><code>{label.call}</code></div>
                    <span className="jdbc-step-result">{summary}</span>
                  </li>
                )
              })}
            </ol>
          ) : <p className="jdbc-unavailable">本次响应未提供分步结果，可查看完整执行日志。</p>}
        </section>

        <div className="jdbc-evidence">
          {rollback && (
            <section className="card jdbc-rollback" aria-labelledby="jdbc-rollback-title">
              <header className="jdbc-section-heading">
                <div>
                  <h2 id="jdbc-rollback-title"><StudioIcon name="rotate" />事务回滚</h2>
                  <p>{rollback.name} · <code>id = {rollback.id}</code> · <code>score</code></p>
                </div>
                {rollbackIndex >= 0 && <span className="jdbc-pill jdbc-mint">步骤 {String(rollbackIndex + 1).padStart(2, '0')}</span>}
              </header>
              <div className="jdbc-snapshot">
                <div className="jdbc-score"><span>更新前</span><strong>{formatScore(rollback.before)}</strong></div>
                <StudioIcon name="arrow-right" className="jdbc-score-arrow" />
                <div className="jdbc-score jdbc-score-pending"><span>更新后 · 未提交</span><strong>{formatScore(rollback.pending)}</strong></div>
                <StudioIcon name="arrow-right" className="jdbc-score-arrow" />
                <div className={'jdbc-score' + (restored ? ' jdbc-score-restored' : '')}>
                  <span>回滚后 · 复查</span><strong>{formatScore(rollback.restored)}</strong>
                </div>
              </div>
              <p className={'jdbc-rollback-note' + (restored ? '' : ' jdbc-mismatch')}>
                <StudioIcon name={restored ? 'check' : 'info'} />
                <span><code>rollback()</code> {restored ? '后分数恢复到更新前的值' : '未观察到预期的分数变化'}</span>
              </p>
            </section>
          )}

          <section className="card jdbc-data" aria-labelledby="jdbc-data-title">
            <header className="jdbc-section-heading">
              <div>
                <h2 id="jdbc-data-title"><StudioIcon name="grid" />最终数据</h2>
                <p><code>SELECT id, name, score FROM user ORDER BY id</code></p>
              </div>
              <span className="jdbc-pill">{rows.length} 行</span>
            </header>
            <table className="jdbc-table" aria-label="user 表的最终数据">
              <thead><tr><th scope="col">id</th><th scope="col">name</th><th scope="col">score</th></tr></thead>
              <tbody>
                {rows.map(({ id, name, score }) => (
                  <tr key={id}><td>{id}</td><td>{name}</td><td>{formatScore(score)}</td></tr>
                ))}
                {!rows.length && <tr><td colSpan={3}>表中没有记录。</td></tr>}
              </tbody>
            </table>
            {transactionSteps?.length ? (
              <ul className="jdbc-checks" aria-label="事务与删除结果">
                {transactionSteps.map(({ id, summary }) => (
                  <li key={id}><StudioIcon name={id === 'rollback' && !restored ? 'info' : 'check'} />{summary}</li>
                ))}
              </ul>
            ) : null}
          </section>
        </div>
      </div>

      <section className="card jdbc-details" aria-label="JDBC 运行明细">
        <details>
          <summary>完整执行日志 <span className="jdbc-pill">{log.length} 条</span></summary>
          <pre className="code">{log.join('\n')}</pre>
        </details>
        <details>
          <summary>JDBC 代码示例</summary>
          <pre className="code">{code}</pre>
        </details>
      </section>
    </>
  )
}
