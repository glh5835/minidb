import React, { useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'

type AnyMap = Record<string, unknown>

/** SQL 执行流程实验（计划书 §23）：SQL → Token → AST → Plan → Executor → Result，全部真实内核产物 */
export default function SqlPipelineLab() {
  const [sql, setSql] = useState('SELECT name FROM student WHERE id = 1;')
  const [result, setResult] = useState<AnyMap | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const [stage, setStage] = useState<'tokens' | 'ast' | 'plan' | 'result'>('tokens')

  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      setResult(await api.pipeline(sql))
      setStage('tokens')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const tokens = (result?.tokens as { type: string; text: string; pos: number }[]) ?? []
  const ast = result?.ast as AnyMap | undefined

  return (
    <div>
      <h1 className="page-title">SQL 执行流程实验</h1>
      <p className="page-sub">输入一条 SELECT，逐步展开 MiniDB 真实执行管线的每一阶段</p>

      <div className="card">
        <div className="flow" style={{ marginBottom: 14 }}>
          {['SQL', 'Token', 'AST', 'Logical/Physical Plan', 'Executor', 'Result'].map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <span className="arrow">↓</span>}
              <span className="node">{s}</span>
            </React.Fragment>
          ))}
        </div>
        <div className="row">
          <input type="text" style={{ flex: 1, fontFamily: 'var(--mono)' }} value={sql}
            onChange={(e) => setSql(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && run()} />
          <button className="btn primary" disabled={busy} onClick={run}>运行管线</button>
        </div>
        {error && <ErrorBox error={error} />}
      </div>

      {result && (
        <div className="card">
          <div className="tabs">
            <button className={stage === 'tokens' ? 'active' : ''} onClick={() => setStage('tokens')}>1. Tokens（词法）</button>
            <button className={stage === 'ast' ? 'active' : ''} onClick={() => setStage('ast')}>2. AST（语法树）</button>
            <button className={stage === 'plan' ? 'active' : ''} onClick={() => setStage('plan')}>3. 计划树</button>
            <button className={stage === 'result' ? 'active' : ''} onClick={() => setStage('result')}>4. 执行结果</button>
          </div>

          {stage === 'tokens' && (
            <div>
              <p className="muted" style={{ fontSize: 13 }}>词法分析器（Lexer）输出的 token 流——脚本拆分、高亮都基于真实词法：</p>
              <div className="table-wrap">
                <table className="grid">
                  <thead><tr><th>#</th><th>类型</th><th>文本</th><th>位置</th></tr></thead>
                  <tbody>
                    {tokens.map((t, i) => (
                      <tr key={i}><td>{i}</td><td className="mono">{t.type}</td><td className="mono">{t.text}</td><td>{t.pos}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {stage === 'ast' && (
            <div>
              <p className="muted" style={{ fontSize: 13 }}>解析器（Parser）产出的 AST 节点（不可变 record）： </p>
              <pre className="code">{JSON.stringify(ast, null, 2)}</pre>
            </div>
          )}

          {stage === 'plan' && (
            <div>
              <p className="muted" style={{ fontSize: 13 }}>优化器基于行数代价估算生成的火山算子树：</p>
              <pre className="code">{String(result.planText)}</pre>
            </div>
          )}

          {stage === 'result' && (
            <div>
              <p className="muted" style={{ fontSize: 13 }}>
                Executor 拉取算子树的真实结果（{String(result.rowCount)} 行，{String(result.elapsedMs)} ms）
                {result.truncated ? ' · 已按 1000 行上限截断' : ''}：
              </p>
              <div className="table-wrap">
                <table className="grid">
                  <thead><tr>{(result.columns as string[]).map((c) => <th key={c}>{c}</th>)}</tr></thead>
                  <tbody>
                    {(result.rows as unknown[][]).map((row, i) => (
                      <tr key={i}>{row.map((v, j) => <td key={j} className={typeof v === 'string' && /^-?\d{15,}$/.test(v) ? 'mono' : ''}>{v === null ? 'NULL' : String(v)}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
