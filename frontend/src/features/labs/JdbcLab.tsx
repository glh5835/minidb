import React, { useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'

type AnyMap = Record<string, unknown>

/** JDBC 实验（计划书 §29）：真实 MiniDbDriver 跑标准 JDBC 流程，独立实验库 */
export default function JdbcLab() {
  const [result, setResult] = useState<AnyMap | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)

  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      setResult(await api.lab.jdbcRun())
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const log = (result?.log as string[]) ?? []
  const rows = (result?.rows as AnyMap[]) ?? []

  return (
    <div>
      <h1 className="page-title">JDBC 实验</h1>
      <p className="page-sub">
        用标准 JDBC 接口（Connection / Statement / PreparedStatement / ResultSet / Transaction）操作真实 MiniDB —— 独立实验库
      </p>

      <div className="card">
        <div className="flow" style={{ marginBottom: 14 }}>
          {['Connection', 'Statement', 'PreparedStatement', 'ResultSet', 'CRUD', 'Transaction'].map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <span className="arrow">→</span>}
              <span className="node">{s}</span>
            </React.Fragment>
          ))}
        </div>
        <button className="btn primary" disabled={busy} onClick={run}>
          {busy ? 'JDBC 流程运行中…' : '▶ 运行 JDBC 实验'}
        </button>
        {error && <ErrorBox error={error} />}
      </div>

      {result && (
        <div className="row" style={{ alignItems: 'flex-start' }}>
          <div className="card" style={{ flex: 1, minWidth: 0 }}>
            <h2>执行日志（真实 JDBC 调用）</h2>
            <pre className="code">{log.map((l) => l).join('\n')}</pre>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="card">
              <h2>数据库最终状态</h2>
              <div className="table-wrap">
                <table className="grid">
                  <thead><tr>{rows[0] && Object.keys(rows[0]).map((k) => <th key={k}>{k}</th>)}</tr></thead>
                  <tbody>
                    {rows.map((r, i) => (
                      <tr key={i}>{Object.values(r).map((v, j) => <td key={j}>{String(v)}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="muted" style={{ fontSize: 13 }}>
                验证点：rollback 后张三分数恢复为 90.0（未提交修改被撤销）；李四 99.5（已提交）；王五（id=3）已删除。
              </p>
            </div>
            <div className="card">
              <h2>本次执行的代码</h2>
              <pre className="code">{String(result.code)}</pre>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
