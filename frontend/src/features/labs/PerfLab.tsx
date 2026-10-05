import React, { useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'

type AnyMap = Record<string, unknown>

/** 性能实验（计划书 §28）：真实测量 SeqScan vs IndexScan，历史数据明确标注 */
export default function PerfLab() {
  const [rows, setRows] = useState(10000)
  const [queries, setQueries] = useState(50)
  const [warmup, setWarmup] = useState(5)
  const [result, setResult] = useState<AnyMap | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)

  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      setResult(await api.lab.perfRun(rows, queries, warmup))
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const results = (result?.results as { mode: string; avgMs: number; totalMs: number; maxMs: number }[]) ?? []
  const env = result?.environment as AnyMap | undefined

  return (
    <div>
      <h1 className="page-title">性能实验</h1>
      <p className="page-sub">同一张表、同一条点查 SQL：无索引 SeqScan vs B+ 树索引 IndexScan —— 本次实时测量</p>

      <div className="card">
        <div className="row">
          <label className="row">数据行数
            <input type="number" min={100} max={200000} step={100} value={rows} style={{ width: 110 }}
              onChange={(e) => setRows(Number(e.target.value))} />
          </label>
          <label className="row">查询次数
            <input type="number" min={1} max={1000} value={queries} style={{ width: 90 }}
              onChange={(e) => setQueries(Number(e.target.value))} />
          </label>
          <label className="row">预热次数
            <input type="number" min={0} max={50} value={warmup} style={{ width: 90 }}
              onChange={(e) => setWarmup(Number(e.target.value))} />
          </label>
          <button className="btn primary" disabled={busy} onClick={run}>
            {busy ? '实验中…（建表 + 灌数据 + 测量）' : '▶ 运行性能实验'}
          </button>
        </div>
        <p className="muted" style={{ fontSize: 12.5, marginTop: 8 }}>
          流程：实验库建表 → 单事务灌入数据 → 无索引预热 + 计时 → 建索引 → 再计时 → 展示两份真实执行计划。
        </p>
        {error && <ErrorBox error={error} />}
      </div>

      {result && (
        <>
          <div className="card">
            <h2>测量结果</h2>
            <div className="table-wrap">
              <table className="grid">
                <thead><tr><th>模式</th><th>平均耗时</th><th>总耗时</th><th>最大单次</th></tr></thead>
                <tbody>
                  {results.map((r) => (
                    <tr key={r.mode}>
                      <td><b>{r.mode}</b></td>
                      <td><b style={{ color: 'var(--primary)' }}>{r.avgMs} ms</b></td>
                      <td className="mono">{r.totalMs} ms</td>
                      <td className="mono">{r.maxMs} ms</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h3 style={{ marginTop: 14 }}>测试环境（如实展示）</h3>
            <ul style={{ fontSize: 13, color: 'var(--muted)' }}>
              <li>数据规模：{String(env?.rows)} 行 · 执行次数：{String(env?.queries)} 次 · 预热：{String(env?.warmup)} 次</li>
              <li>测试 SQL：<span className="mono">{String(env?.sql)}</span></li>
              <li>本次运行时间：{String(env?.ranAt)}</li>
            </ul>
          </div>
          <div className="card">
            <h2>执行计划对比（真实优化器输出）</h2>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <div style={{ flex: 1 }}>
                <h3>无索引（建索引前）</h3>
                <pre className="code">{String(result.planSeqScan)}</pre>
              </div>
              <div style={{ flex: 1 }}>
                <h3>有索引（建索引后）</h3>
                <pre className="code">{String(result.planIndexScan)}</pre>
              </div>
            </div>
          </div>
        </>
      )}

      <div className="card">
        <h2>关于历史测试结果</h2>
        <p className="muted" style={{ fontSize: 13 }}>
          项目根目录 <span className="mono">PERF.md</span> 中记录的是<b>开发阶段的历史测试结果</b>（当时的硬件与环境），
          不能与本次实时测量混为一谈。要看当前环境的真实表现，请运行上方实验。
        </p>
      </div>
    </div>
  )
}
