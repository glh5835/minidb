import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { useApp } from '../../stores/app'
import { Empty, ErrorBox, Modal, PlanTree } from '../../components/ui'
import { nav } from '../../router'
import type { BTreeNode, BTreeSnapshot, IndexInfo, SchemaInfo } from '../../types'

const NODE_W = 108
const NODE_H = 46
const GAP_X = 14
const GAP_Y = 70

/** 索引与计划（计划书 §17-§19）：索引管理 + B+ 树快照 SVG + 执行计划查看 */
export default function IndexPlanPage() {
  const { session, refresh, confirm, notify } = useApp()
  const [indexes, setIndexes] = useState<IndexInfo[]>([])
  const [error, setError] = useState<ApiFailure | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [snap, setSnap] = useState<BTreeSnapshot | null>(null)
  const [node, setNode] = useState<BTreeNode | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [planSql, setPlanSql] = useState('SELECT * FROM student WHERE id = 1;')
  const [plan, setPlan] = useState<{ text: string; tree: import('../../types').PlanNode[] } | null>(null)
  const [planErr, setPlanErr] = useState<ApiFailure | null>(null)

  const load = useCallback(async () => {
    if (!session?.hasDb) return
    try {
      const list = await api.indexes()
      setIndexes(list)
      if (selected && !list.some((ix) => ix.name === selected)) setSelected(null)
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    }
  }, [session, selected])

  useEffect(() => { load() }, [load])

  const loadBtree = useCallback(async (name: string) => {
    try {
      setSnap(await api.btree(name))
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    }
  }, [])

  useEffect(() => {
    if (selected) loadBtree(selected)
    else setSnap(null)
  }, [selected, loadBtree])

  if (!session?.hasDb) {
    return <Empty icon="🗂️" message="尚未打开数据库">
      <button className="btn primary" onClick={() => nav('/start')}>去开始页</button>
    </Empty>
  }

  const doDrop = async (name: string) => {
    if (!(await confirm({
      title: '删除索引',
      message: `确定删除索引 ${name}？该 B+ 树的全部节点页将被释放。`,
      actionText: `删除索引 ${name}`,
    }))) return
    try {
      await api.dropIndex(name)
      notify(`索引 ${name} 已删除`, 'success')
      await load()
      await refresh()
    } catch (e) {
      if (e instanceof ApiFailure) notify(e.message, 'error')
    }
  }

  return (
    <div>
      <h1 className="page-title">索引与计划</h1>
      <p className="page-sub">管理真实 B+ 树索引（均为唯一索引）· 查看结构快照 · 查看优化器执行计划</p>
      {error && <ErrorBox error={error} />}

      <div className="row" style={{ alignItems: 'flex-start' }}>
        <div className="card" style={{ width: 470, flexShrink: 0 }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2>索引</h2>
            <button className="btn small primary" onClick={() => setCreateOpen(true)}>＋ 创建索引</button>
          </div>
          {indexes.length === 0 ? <div className="muted">暂无索引。</div> : (
            <div className="table-wrap">
              <table className="grid">
                <thead><tr><th>索引 / 表.列</th><th>规模</th><th>操作</th></tr></thead>
                <tbody>
                  {indexes.map((ix) => (
                    <tr key={ix.name} style={{ background: selected === ix.name ? 'var(--primary-light)' : undefined }}>
                      <td style={{ cursor: 'pointer' }} onClick={() => setSelected(ix.name)}>
                        <b className="mono">{ix.name}</b><br />
                        <span className="muted" style={{ fontSize: 12 }}>{ix.table}.{ix.column} · {ix.keyType}</span>
                      </td>
                      <td className="muted" style={{ fontSize: 12 }}>
                        高 {ix.height} · {ix.totalKeys} 键<br />
                        叶 {ix.leafNodes} · 利用率 {(ix.avgLeafUtilization * 100).toFixed(0)}%
                      </td>
                      <td>
                        <div className="cell-actions">
                          <button className="btn small" onClick={() => setSelected(ix.name)}>查看结构</button>
                          <button className="btn small danger" onClick={() => doDrop(ix.name)}>删除</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="card">
            <h2>B+ 树结构{selected ? ` · ${selected}` : ''}</h2>
            {!selected ? <div className="muted">选择左侧索引查看真实 B+ 树快照（后端只读接口 → 前端 SVG 渲染）。</div>
              : !snap ? <div className="muted">加载中…</div>
                : <BTreeSvg snap={snap} onNode={setNode} />}
          </div>
        </div>
      </div>

      <div className="card">
        <h2>执行计划</h2>
        <div className="row">
          <input type="text" style={{ flex: 1, fontFamily: 'var(--mono)' }} value={planSql}
            onChange={(e) => setPlanSql(e.target.value)} />
          <button className="btn primary" onClick={async () => {
            setPlanErr(null)
            try {
              setPlan(await api.plan(planSql))
            } catch (e) {
              if (e instanceof ApiFailure) setPlanErr(e)
            }
          }}>查看计划</button>
        </div>
        {planErr && <ErrorBox error={planErr} />}
        {plan && <div style={{ marginTop: 12 }}><PlanTree tree={plan.tree} text={plan.text} /></div>}
      </div>

      {createOpen && <CreateIndexModal onClose={() => setCreateOpen(false)} onDone={async () => {
        setCreateOpen(false)
        await load()
        await refresh()
      }} />}
      {node && <NodeModal node={node} onClose={() => setNode(null)} />}
    </div>
  )
}

function CreateIndexModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { tables, notify } = useApp()
  const [name, setName] = useState('')
  const [table, setTable] = useState(tables[0]?.name ?? '')
  const [column, setColumn] = useState('')
  const [schema, setSchema] = useState<SchemaInfo | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (table) api.schema(table).then((s) => {
      setSchema(s)
      setColumn(s.columns[0]?.name ?? '')
    }).catch(() => setSchema(null))
  }, [table])

  const create = async () => {
    setErr(null)
    if (!name.trim() || !table || !column) return
    try {
      await api.createIndex(name.trim(), table, column)
      notify(`索引 ${name} 已创建`, 'success')
      onDone()
    } catch (e) {
      if (e instanceof ApiFailure) setErr(e.message)
    }
  }

  return (
    <Modal title="创建索引" onClose={onClose}>
      <div style={{ display: 'grid', gap: 10 }}>
        <label className="row"><span style={{ width: 80 }}>索引名</span>
          <input type="text" style={{ flex: 1 }} value={name} placeholder="如 idx_student_age"
            onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="row"><span style={{ width: 80 }}>表</span>
          <select style={{ flex: 1 }} value={table} onChange={(e) => setTable(e.target.value)}>
            {tables.map((t) => <option key={t.name}>{t.name}</option>)}
          </select>
        </label>
        <label className="row"><span style={{ width: 80 }}>列</span>
          <select style={{ flex: 1 }} value={column} onChange={(e) => setColumn(e.target.value)}>
            {schema?.columns.map((c) => <option key={c.name}>{c.name}</option>)}
          </select>
        </label>
        <p className="muted" style={{ fontSize: 12.5 }}>
          MiniDB 的索引为 B+ 树唯一索引，键类型支持 INT / BIGINT / VARCHAR（≤255 字节）。NULL 值不入索引；
          若现有数据存在重复值，建索引会失败。
        </p>
        {err && <ErrorBox error={err} />}
      </div>
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>取消</button>
        <button className="btn primary" onClick={create}>创建索引</button>
      </div>
    </Modal>
  )
}

function NodeModal({ node, onClose }: { node: BTreeNode; onClose: () => void }) {
  return (
    <Modal title={`节点 · 页 ${node.pageId}（${node.kind}）`} onClose={onClose}>
      <div className="kv" style={{ marginBottom: 12 }}>
        <div className="item"><div className="k">Page ID</div><div className="v">{node.pageId}</div></div>
        <div className="item"><div className="k">键数</div><div className="v">{node.keys.length}</div></div>
        <div className="item"><div className="k">使用率</div><div className="v">{((node.usedBytes / node.totalBytes) * 100).toFixed(0)}%</div></div>
        <div className="item"><div className="k">占用字节</div><div className="v">{node.usedBytes}/{node.totalBytes}</div></div>
      </div>
      <h3>Keys</h3>
      <pre className="code">{node.keys.join('  |  ')}</pre>
      {node.rids && <><h3>行 RID</h3><pre className="code">{node.rids.join('  |  ')}</pre></>}
      {node.children && <><h3>子节点页</h3><pre className="code">{node.children.join(', ')}</pre></>}
      {node.kind === 'LEAF' && <p className="muted" style={{ fontSize: 12.5 }}>叶链：prev={node.prev || '∅'} · next={node.next || '∅'}</p>}
    </Modal>
  )
}

/** Snapshot → SVG（前端只做渲染，数据来自真实内核快照） */
function BTreeSvg({ snap, onNode }: { snap: BTreeSnapshot; onNode: (n: BTreeNode) => void }) {
  const byId = useMemo(() => {
    const m = new Map<number, BTreeNode>()
    snap.nodes.forEach((n) => m.set(n.pageId, n))
    return m
  }, [snap])

  // 分层：从根 BFS
  const levels = useMemo(() => {
    const levels: BTreeNode[][] = []
    let frontier = [snap.root]
    while (frontier.length) {
      levels.push(frontier.map((id) => byId.get(id)!).filter(Boolean))
      const next: number[] = []
      for (const n of frontier) {
        const node = byId.get(n)
        if (node?.children) next.push(...node.children)
      }
      if (next.length === frontier.length) break // 防御
      frontier = next
    }
    return levels
  }, [snap, byId])

  const maxWidth = Math.max(...levels.map((lv) => lv.length)) * (NODE_W + GAP_X) - GAP_X
  const height = levels.length * (NODE_H + GAP_Y) - GAP_Y + 20
  const viewBoxW = Math.min(maxWidth, 1600)

  return (
    <div style={{ overflowX: 'auto' }}>
      <svg width="100%" viewBox={`0 0 ${Math.max(maxWidth, 320)} ${height}`} style={{ minWidth: 640 }} role="img" aria-label="B+ 树结构">
        {/* 连线：父 → 子 */}
        {levels.slice(0, -1).map((lv, li) =>
          lv.map((node, ni) => {
            const childIds = node.children ?? []
            return childIds.map((cid, ci) => {
              const child = byId.get(cid)
              if (!child) return null
              const childLevel = levels[li + 1]
              const childIdx = childLevel.findIndex((c) => c.pageId === cid)
              if (childIdx < 0) return null
              const x1 = levelX(lv.length, ni, viewBoxW, maxWidth) + NODE_W / 2
              const y1 = li * (NODE_H + GAP_Y) + NODE_H
              const x2 = levelX(childLevel.length, childIdx, viewBoxW, maxWidth) + NODE_W / 2
              const y2 = (li + 1) * (NODE_H + GAP_Y)
              return <line key={`${node.pageId}-${cid}`} x1={x1} y1={y1} x2={x2} y2={y2} stroke="var(--border-strong)" strokeWidth={1.4} />
            })
          }))}
        {/* 叶链 prev/next 虚线 */}
        {levels.length > 1 && levels[levels.length - 1].map((n, i, arr) => (
          i < arr.length - 1 && n.next === arr[i + 1].pageId ? (
            <line key={`leaf-${n.pageId}`}
              x1={levelX(arr.length, i, viewBoxW, maxWidth) + NODE_W}
              y1={height - NODE_H + NODE_H / 2}
              x2={levelX(arr.length, i + 1, viewBoxW, maxWidth)}
              y2={height - NODE_H + NODE_H / 2}
              stroke="var(--primary)" strokeWidth={1.2} strokeDasharray="4 3" />
          ) : null))}
        {/* 节点 */}
        {levels.map((lv, li) =>
          lv.map((n, ni) => {
            const x = levelX(lv.length, ni, viewBoxW, maxWidth)
            const y = li * (NODE_H + GAP_Y)
            const isRoot = n.pageId === snap.root
            const util = n.usedBytes / n.totalBytes
            return (
              <g key={n.pageId} style={{ cursor: 'pointer' }} onClick={() => onNode(n)}>
                <rect x={x} y={y} width={NODE_W} height={NODE_H} rx={8}
                  fill={n.kind === 'LEAF' ? '#fff' : 'var(--primary-light)'}
                  stroke={isRoot ? 'var(--primary)' : 'var(--border-strong)'}
                  strokeWidth={isRoot ? 2.2 : 1.2} />
                <rect x={x + 4} y={y + NODE_H - 9} width={(NODE_W - 8) * util} height={4} rx={2} fill="var(--primary)" opacity={0.55} />
                <text x={x + 8} y={y + 15} fontSize={10} fill="var(--muted)">
                  {n.kind === 'LEAF' ? '叶' : '内'} · 页{n.pageId} · {n.keys.length}键
                </text>
                <text x={x + 8} y={y + 30} fontSize={11} fontFamily="var(--mono)" fill="var(--text)">
                  {n.keys.length === 0 ? '(空)' : trimKeys(n.keys, 12)}
                </text>
              </g>
            )
          }))}
      </svg>
      <div className="muted" style={{ fontSize: 12.5, marginTop: 6 }}>
        树高 {snap.height} · 节点 {snap.nodes.length} 个 · 根页 {snap.root} · 键类型 {snap.stringKeys ? 'VARCHAR' : 'INT/BIGINT'} ·
        绿色虚线为叶链（范围扫描顺序）· 点击节点查看详情 · 使用率 = 实际字节 / 4096
      </div>
    </div>
  )
}

function levelX(levelCount: number, idx: number, viewBoxW: number, maxWidth: number): number {
  // 该层在 max(maxWidth, viewBoxW) 宽度内居中排布
  const total = Math.max(maxWidth, 320)
  const startX = Math.max(0, (total - levelCount * (NODE_W + GAP_X) + GAP_X) / 2)
  return startX + idx * (NODE_W + GAP_X)
}

function trimKeys(keys: string[], maxLen: number): string {
  const joined = keys.join(',')
  return joined.length > maxLen ? joined.slice(0, maxLen) + '…' : joined
}
