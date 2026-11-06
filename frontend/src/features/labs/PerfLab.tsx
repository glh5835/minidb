import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox } from '../../components/ui'
import { useApp } from '../../stores/app'
import type { PerfLabResult } from '../../types'
import './PerfLab.css'

const timing = new Intl.NumberFormat('en-US', { maximumFractionDigits: 6, useGrouping: false })
const count = new Intl.NumberFormat('zh-CN')

const settings = [
  { name: 'rows', label: '数据行数', unit: '行', min: 100, max: 200000, initial: 10000 },
  { name: 'queries', label: '每组查询次数', unit: '次', min: 1, max: 1000, initial: 50 },
  { name: 'warmup', label: '每组预热次数', unit: '次', min: 0, max: 50, initial: 5 },
] as const

const chartModes = {
  SeqScan: { detail: '无索引 · 全表扫描', color: 'seq' },
  IndexScan: { detail: 'B+ 树索引 · 等值查找', color: 'index' },
}

function tickSize(max: number) {
  if (max === 0) return 0.25
  const target = max / 4
  const magnitude = 10 ** Math.floor(Math.log10(target))
  const factor = [1, 2, 2.5, 5, 10].find((candidate) => candidate >= target / magnitude) ?? 10
  return factor * magnitude
}

/** 真实测量来自独立实验库。 */
export default function PerfLab() {
  const { ready, connected } = useApp()
  const [result, setResult] = useState<PerfLabResult | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const running = useRef(false)

  const run = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (running.current || !ready) return
    const data = new FormData(event.currentTarget)
    running.current = true
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      setResult(await api.lab.perfRun(Number(data.get('rows')), Number(data.get('queries')), Number(data.get('warmup'))))
    } catch (e) {
      setError(e instanceof ApiFailure ? e : new ApiFailure('CLIENT_ERROR', '无法运行性能实验。'))
    } finally {
      running.current = false
      setBusy(false)
    }
  }

  return (
    <div className="perf-view">
      <header className="perf-intro">
        <div className="perf-title"><h1>性能实验</h1><span className="perf-badge lab"><StudioIcon name="flask" />独立实验库</span></div>
        <p>用同一张表和同一种点查 SQL，观察建立 B+ 树索引前后的查询耗时。</p>
      </header>

      <form className="card perf-panel" onSubmit={(event) => void run(event)} aria-labelledby="perf-setup-title">
        <div className="perf-panel-head">
          <div>
            <h2 id="perf-setup-title"><StudioIcon name="activity" />实验配置</h2>
            <p><code>perf.exp.db</code> · 建索引前后分别预热并计时</p>
          </div>
          <button className="btn primary" type="submit" disabled={busy || !ready}>
            <StudioIcon name="play" />{busy ? '实验运行中…' : '运行性能实验'}
          </button>
        </div>
        <div className="perf-inputs">
          {settings.map(({ name, label, unit, min, max, initial }) => (
            <label className="perf-setting" key={name}>
              {label}
              <span className="perf-field">
                <input type="number" name={name} min={min} max={max} defaultValue={initial} step={1} required disabled={busy} />
                <span aria-hidden="true">{unit}</span>
              </span>
            </label>
          ))}
          <div className="perf-setting">
            点查 SQL
            <div className="perf-field perf-sql"><StudioIcon name="terminal" />
              <code><b>SELECT</b> * <b>FROM</b> perf_t <b>WHERE</b> id = ?</code>
            </div>
          </div>
        </div>
      </form>

      {error && <div role="alert"><ErrorBox error={error} /></div>}
      {result ? <PerfResults result={result} /> : (
        <section className="card perf-panel perf-empty" aria-labelledby="perf-empty-title">
          <div className="perf-panel-head"><h2 id="perf-empty-title"><StudioIcon name="gauge" />查询耗时对比</h2></div>
          <p role="status">{busy ? '正在装载数据、预热并测量两种扫描方式…'
            : !ready ? '正在连接 MiniDB 服务…'
              : error ? '本次实验未返回结果，可以重新运行。'
                : !connected ? 'MiniDB 服务未连接，恢复连接后可以运行实验。'
                  : '运行实验后，此处将显示实际耗时和执行计划。'}</p>
        </section>
      )}

      <p className="perf-history"><StudioIcon name="info" /><span><code>PERF.md</code> 记录开发阶段在当时环境下的历史测试结果。</span></p>
    </div>
  )
}

function PerfResults({ result }: { result: PerfLabResult }) {
  const { environment, planSeqScan, planIndexScan, rowsLoaded } = result
  const measurements = result.results.map((sample) => {
    const name = sample.mode.split('（', 1)[0]
    return { ...sample, name, display: chartModes[name as keyof typeof chartModes] }
  })
  const tick = tickSize(Math.max(0, ...measurements.map(({ avgMs }) => avgMs)))
  const ticks = [0, 1, 2, 3, 4].map((i) => i * tick)
  const scale = 4 * tick

  return (
    <>
      <section className="card perf-panel" aria-labelledby="perf-results-title">
        <div className="perf-panel-head">
          <div>
            <h2 id="perf-results-title"><StudioIcon name="gauge" />查询耗时对比</h2>
            <p>随机 ID 点查 · {count.format(rowsLoaded)} 行 · 每组计时 {count.format(environment.queries)} 次 · 预热 {count.format(environment.warmup)} 次</p>
          </div>
          <span className="perf-badge lab" role="status">本次测量</span>
        </div>
        <div className="perf-chart" role="img" aria-label={'平均单次耗时。' + measurements.map(({ name, avgMs }) => name + ' ' + timing.format(avgMs) + ' 毫秒').join('，')}>
          <div className="perf-axis-label">平均单次耗时 <span>· ms</span></div>
          {measurements.map(({ mode, name, avgMs, display }) => {
            const width = `${avgMs / scale * 100}%`
            return (
              <div className="perf-chart-row" key={mode}>
                <div className="perf-series"><strong>{name}</strong>{display && <span>{display.detail}</span>}</div>
                <div className="perf-track">
                  <div className={'perf-bar ' + (display?.color ?? '')} style={{ width }} />
                  <strong className="perf-value" style={{ left: width }}>{timing.format(avgMs)} <small>ms</small></strong>
                </div>
              </div>
            )
          })}
          <div className="perf-chart-axis">
            {ticks.map((value, i) => <span key={i} style={{ left: `${i * 25}%` }}>{timing.format(value)}</span>)}
          </div>
        </div>
        <div className="perf-table-wrap">
          <table className="perf-table" aria-label="本次查询耗时明细">
            <thead><tr><th scope="col">扫描方式</th><th scope="col">平均单次</th><th scope="col">{count.format(environment.queries)} 次总耗时</th><th scope="col">最慢单次</th></tr></thead>
            <tbody>
              {measurements.map(({ mode, name, avgMs, totalMs, maxMs, display }) => (
                <tr key={mode}>
                  <th scope="row"><span className={'perf-swatch ' + (display?.color ?? '')} aria-hidden="true" />{name}</th>
                  <td>{timing.format(avgMs)} ms</td><td>{timing.format(totalMs)} ms</td><td>{timing.format(maxMs)} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="perf-meta">
          <span>预热与建索引耗时不计入</span>
          <span><code>{environment.sql}</code></span>
          <span>运行时间 <time dateTime={environment.ranAt}>{environment.ranAt.replace('T', ' ').split('.')[0]}</time></span>
        </p>
      </section>
      <section className="card perf-panel" aria-labelledby="perf-plans-title">
        <div className="perf-panel-head">
          <div><h2 id="perf-plans-title"><StudioIcon name="tree" />执行计划对比</h2><p><code>EXPLAIN SELECT * FROM perf_t WHERE id = 1</code></p></div>
          <span className="perf-badge">优化器输出</span>
        </div>
        <div className="perf-plan-grid">
          <PerfPlan title="建索引前" context="无索引" text={planSeqScan} />
          <PerfPlan title="建索引后" context="idx_perf_id" text={planIndexScan} indexed />
        </div>
      </section>
    </>
  )
}

function PerfPlan({ title, context, text, indexed = false }: {
  title: string
  context: string
  text: string
  indexed?: boolean
}) {
  const lines = text.trimEnd().split('\n')
  return (
    <section className={'perf-plan' + (indexed ? ' indexed' : '')}>
      <h3>{title} {indexed ? <code>{context}</code> : <span>{context}</span>}</h3>
      <pre className="perf-tree">
        {lines.map((line, i) => {
          const operator = line.trimStart()
          const depth = (line.length - operator.length) / 2
          const scan = operator.startsWith('SeqScan(') ? ' seq' : operator.startsWith('IndexScan(') ? ' index' : ''
          return (
            <code key={i} className={(depth ? 'perf-child' : '') + scan} style={{ marginInlineStart: depth * 18 }}>
              {operator}{i < lines.length - 1 ? '\n' : ''}
            </code>
          )
        })}
      </pre>
    </section>
  )
}
