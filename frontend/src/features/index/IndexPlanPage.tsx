import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox, Modal } from '../../components/ui'
import { useApp } from '../../stores/app'
import type { BTreeNode, BTreeSnapshot, IndexInfo, PlanNode, PlanResult, SchemaInfo } from '../../types'
import './IndexPlanPage.css'

const NODE_WIDTH = 180
const NODE_HEIGHT = 64
const HORIZONTAL_GAP = 28
const VERTICAL_GAP = 44
const PADDING = 18

type ReadResult<T> = { data: T } | { error: ApiFailure | string }

function readError(error: unknown): ApiFailure | string {
  return error instanceof ApiFailure ? error : '读取失败，请重试。'
}

function indexKeyType(type: string) {
  if (type === 'STRING') return 'VARCHAR'
  if (type === 'LONG') return 'INT / BIGINT'
  return type
}

export default function IndexPlanPage() {
  const { ready, session } = useApp()

  return (
    <section className="index-view">
      <header className="index-heading">
        <h1>索引与计划</h1>
        <p>管理唯一索引、查看 B+ 树节点与叶链，分析 SELECT 查询的执行计划。</p>
      </header>
      {!ready ? <p className="index-state" role="status">正在连接 MiniDB…</p> : session?.hasDb ? (
        <IndexWorkspace key={session.sessionId + '/' + session.dbPath} />
      ) : (
        <div className="card index-empty">
          <span className="index-object-mark"><StudioIcon name="database" /></span>
          <p>尚未打开数据库</p>
          <a className="btn primary" href="#/start">去开始页</a>
        </div>
      )}
    </section>
  )
}

function IndexWorkspace() {
  const { session, tables, refresh, confirm, notify } = useApp()
  const [revision, setRevision] = useState(0)
  const [response, setResponse] = useState<{ revision: number; result: ReadResult<IndexInfo[]> } | null>(null)
  const [selection, setSelection] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [dropping, setDropping] = useState(false)
  const [dropError, setDropError] = useState<ApiFailure | string | null>(null)

  useEffect(() => {
    let current = true
    void api.indexes().then((data) => {
      if (current) setResponse({ revision, result: { data } })
    }, (error: unknown) => {
      if (current) setResponse({ revision, result: { error: readError(error) } })
    })
    return () => { current = false }
  }, [revision])

  const result = response?.revision === revision ? response.result : null
  const indexes = result && 'data' in result ? result.data : []
  const selected = indexes.find((index) => index.name === selection) ?? indexes[0]
  const transactionActive = session?.txn.active ?? false
  const actionsDisabled = dropping || createOpen || transactionActive
  const reload = () => setRevision((current) => current + 1)

  const drop = async (name: string) => {
    setDropping(true)
    setDropError(null)
    try {
      if (!await confirm({
        title: '删除索引',
        message: '确定删除索引 ' + name + '？该 B+ 树的全部节点页将被释放。',
        actionText: '删除索引 ' + name,
      })) return
      await api.dropIndex(name)
      notify('索引 ' + name + ' 已删除', 'success')
      if (selected?.name === name) setSelection('')
      reload()
      await refresh()
    } catch (error) {
      setDropError(error instanceof ApiFailure ? error : '删除索引失败。')
    } finally {
      setDropping(false)
    }
  }

  return (
    <>
      <div className="index-layout">
        <aside className="card index-list" aria-label="数据库索引">
          <div className="index-list-head">
            <h2>索引列表 {result && 'data' in result && <span className="index-count">{indexes.length}</span>}</h2>
            <button
              className="btn primary" type="button" disabled={actionsDisabled || !tables.length}
              title={transactionActive ? '请结束当前事务后再创建索引' : undefined}
              onClick={() => { setDropError(null); setCreateOpen(true) }}
            ><StudioIcon name="plus" />创建索引</button>
          </div>

          {dropError && <div role="alert"><ErrorBox error={dropError} /></div>}
          {!result && <p className="index-state" role="status">正在读取索引…</p>}
          {result && 'error' in result && (
            <div className="index-read-error" role="alert">
              <ErrorBox error={result.error} />
              <button className="btn small" type="button" onClick={reload}>重试读取索引</button>
            </div>
          )}
          {result && 'data' in result && (
            <div className="index-items">
              {!indexes.length && <p className="index-state">暂无索引。</p>}
              {indexes.map((index) => (
                <div className="index-object" key={index.name} data-selected={index.name === selected?.name}>
                  <button
                    className="index-object-button" type="button" aria-label={'查看 ' + index.name + ' 结构'}
                    aria-pressed={index.name === selected?.name} onClick={() => setSelection(index.name)}
                  >
                    <span className="index-object-mark"><StudioIcon name="key" /></span>
                    <span className="index-object-main">
                      <strong className="mono" title={index.name}>{index.name}</strong>
                      <span title={index.table + '.' + index.column}>{index.table}.{index.column} · {indexKeyType(index.keyType)}</span>
                      <span>高 {index.height} · {index.totalKeys} 键</span>
                    </span>
                  </button>
                  <button className="index-remove" type="button" aria-label={'删除索引 ' + index.name} disabled={actionsDisabled}
                    title={transactionActive ? '请结束当前事务后再删除索引' : '删除索引 ' + index.name}
                    onClick={() => drop(index.name)}><StudioIcon name="trash" /></button>
                </div>
              ))}
            </div>
          )}
          <div className="index-list-foot"><StudioIcon name="info" /><span>选择索引查看节点分布。</span></div>
        </aside>

        {selected ? <TreePanel key={selected.name} index={selected} /> : (
          <section className="card index-tree">
            <header className="index-tree-head"><h2><StudioIcon name="tree" />B+ 树结构</h2></header>
            <div className="index-empty"><p>选择左侧索引后查看 B+ 树快照。</p></div>
          </section>
        )}
      </div>

      <ExecutionPlan indexRevision={revision} />

      {createOpen && <CreateIndexModal onClose={() => setCreateOpen(false)} onDone={(name) => {
        setCreateOpen(false)
        setSelection(name)
        reload()
        void refresh()
      }} />}
    </>
  )
}

function CreateIndexModal({ onClose, onDone }: { onClose: () => void; onDone: (name: string) => void }) {
  const { tables, notify } = useApp()
  const [name, setName] = useState('')
  const [table, setTable] = useState(tables[0]?.name ?? '')
  const [column, setColumn] = useState('')
  const [revision, setRevision] = useState(0)
  const [response, setResponse] = useState<{ table: string; revision: number; result: ReadResult<SchemaInfo> } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiFailure | string | null>(null)

  useEffect(() => {
    if (!table) return
    let current = true
    void api.schema(table).then((data) => {
      if (current) setResponse({ table, revision, result: { data } })
    }, (error: unknown) => {
      if (current) setResponse({ table, revision, result: { error: readError(error) } })
    })
    return () => { current = false }
  }, [table, revision])

  const schemaResult = response?.table === table && response.revision === revision ? response.result : null
  const eligible = schemaResult && 'data' in schemaResult ? schemaResult.data.columns.filter((entry) =>
    entry.type === 'INT' || entry.type === 'BIGINT' ||
      (entry.type === 'VARCHAR' && entry.maxLength !== null && entry.maxLength > 0 && entry.maxLength <= 255),
  ) : []
  const selectedColumn = eligible.find((entry) => entry.name === column) ?? eligible[0]

  const create = async () => {
    if (busy || !name.trim() || !table || !selectedColumn) return
    setError(null)
    setBusy(true)
    try {
      const indexName = name.trim()
      await api.createIndex(indexName, table, selectedColumn.name)
      notify('索引 ' + indexName + ' 已创建', 'success')
      onDone(indexName)
    } catch (error) {
      setError(error instanceof ApiFailure ? error : '创建索引失败。')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="创建索引" onClose={() => { if (!busy) onClose() }}>
      <form onSubmit={(event) => { event.preventDefault(); void create() }}>
        <div className="index-fields">
          <label><span>索引名</span><input type="text" autoFocus required value={name} disabled={busy} placeholder="如 idx_student_age"
            onChange={(event) => setName(event.target.value)} /></label>
          <label><span>表</span><select value={table} disabled={busy} onChange={(event) => {
            setTable(event.target.value)
            setColumn('')
            setError(null)
          }}>{tables.map((entry) => <option key={entry.name}>{entry.name}</option>)}</select></label>
          <label><span>列</span><select value={selectedColumn?.name ?? ''} disabled={busy || !eligible.length}
            onChange={(event) => setColumn(event.target.value)}>
            {eligible.length ? eligible.map((entry) => <option key={entry.name}>{entry.name}</option>) : (
              <option value="">{!schemaResult ? '正在读取列…' : '没有可用列'}</option>
            )}
          </select></label>
        </div>
        <p className="index-create-note">索引键支持 INT、BIGINT 和 VARCHAR（列声明长度不超过 255 字节）。同一列的重复值会导致建索引失败。</p>
        {schemaResult && 'error' in schemaResult && (
          <div role="alert"><ErrorBox error={schemaResult.error} />
            <button className="btn small" type="button" onClick={() => setRevision((current) => current + 1)}>重试读取列</button>
          </div>
        )}
        {error && <div role="alert"><ErrorBox error={error} /></div>}
        <div className="modal-actions">
          <button className="btn" type="button" disabled={busy} onClick={onClose}>取消</button>
          <button className="btn primary" type="submit" disabled={busy || !name.trim() || !table || !selectedColumn}>创建索引</button>
        </div>
      </form>
    </Modal>
  )
}

function TreePanel({ index }: { index: IndexInfo }) {
  const [revision, setRevision] = useState(0)
  const [response, setResponse] = useState<{ revision: number; result: ReadResult<BTreeSnapshot> } | null>(null)
  const [node, setNode] = useState<BTreeNode | null>(null)
  const [selectedPage, setSelectedPage] = useState<number | null>(null)
  const nodeTrigger = useRef<SVGGElement | null>(null)

  useEffect(() => {
    let current = true
    void api.btree(index.name).then((data) => {
      if (current) setResponse({ revision, result: { data } })
    }, (error: unknown) => {
      if (current) setResponse({ revision, result: { error: readError(error) } })
    })
    return () => { current = false }
  }, [index.name, revision])

  const result = response?.revision === revision ? response.result : null
  const snapshot = result && 'data' in result ? result.data : null
  const reload = () => {
    setSelectedPage(null)
    setRevision((current) => current + 1)
  }

  return (
    <section className="card index-tree">
      <header className="index-tree-head">
        <div><h2><StudioIcon name="tree" />B+ 树结构</h2><p className="mono" title={index.name}>{index.name} · {index.table}.{index.column}</p></div>
        <button className="btn small" type="button" disabled={!result} onClick={reload}><StudioIcon name="refresh" />刷新结构</button>
      </header>
      {!result && <div className="index-empty" role="status"><p>正在读取 B+ 树快照…</p></div>}
      {result && 'error' in result && <div className="index-read-error" role="alert"><ErrorBox error={result.error} /></div>}
      {snapshot && (
        <>
          <div className="index-meta" aria-label="B+ 树快照概要">
            <span>树高 <strong>{snapshot.height}</strong></span>
            <span>节点 <strong>{snapshot.nodes.length}</strong></span>
            <span>根页 <strong>{snapshot.root}</strong></span>
            <span>键数 <strong>{snapshot.nodes.reduce((total, entry) => total + (entry.kind === 'LEAF' ? entry.keys.length : 0), 0)}</strong></span>
            <span>键类型 <strong>{snapshot.stringKeys ? 'VARCHAR' : 'INT / BIGINT'}</strong></span>
          </div>
          <BTreeCanvas snapshot={snapshot} selectedPage={selectedPage} onNode={(entry, trigger) => {
            nodeTrigger.current = trigger
            setSelectedPage(entry.pageId)
            setNode(entry)
          }} />
          <div className="index-tree-foot">
            <div className="index-legend">
              <span><i className="index-legend-root" />内部节点</span><span><i />叶节点</span><span><i className="index-legend-chain" />叶链</span>
              {selectedPage !== null && <span><i className="index-legend-selected" />所选节点路径</span>}
            </div>
            <span>选择节点查看键、RID 与页占用。</span>
          </div>
        </>
      )}
      {node && <NodeModal node={node} onClose={() => {
        setNode(null)
        nodeTrigger.current?.focus()
      }} />}
    </section>
  )
}

interface NodePosition {
  node: BTreeNode
  children: NodePosition[]
  x: number
  depth: number
}

/** 叶节点按实际子页顺序铺开，内部节点居中于其子树。 */
function layoutTree(snapshot: BTreeSnapshot) {
  const byId = new Map(snapshot.nodes.map((node) => [node.pageId, node]))
  const positioned = new Map<number, NodePosition>()
  const parents = new Map<number, number>()
  const visited = new Set<number>()
  let nextColumn = 0
  let depth = 0

  function visit(id: number, level: number): NodePosition | null {
    const node = byId.get(id)
    if (!node || visited.has(id)) return null
    visited.add(id)
    depth = Math.max(depth, level)
    const children = (node.children ?? []).map((child) => visit(child, level + 1)).filter((child): child is NodePosition => child !== null)
    children.forEach((child) => parents.set(child.node.pageId, id))
    const x = children.length ? (children[0].x + children[children.length - 1].x) / 2 :
      PADDING + NODE_WIDTH / 2 + nextColumn++ * (NODE_WIDTH + HORIZONTAL_GAP)
    const position = { node, children, x, depth: level }
    positioned.set(id, position)
    return position
  }

  visit(snapshot.root, 0)
  const width = 2 * PADDING + Math.max(1, nextColumn) * NODE_WIDTH + Math.max(0, nextColumn - 1) * HORIZONTAL_GAP
  const treeHeight = NODE_HEIGHT + depth * (NODE_HEIGHT + VERTICAL_GAP)
  const height = Math.max(212, treeHeight + 2 * PADDING)
  const positions = [...positioned.values()].sort((a, b) => a.depth - b.depth || a.x - b.x)
  return { positioned, parents, positions, width, height, y: (level: number) => (height - treeHeight) / 2 + level * (NODE_HEIGHT + VERTICAL_GAP) }
}

function previewKeys(keys: string[]) {
  if (!keys.length) return '（空）'
  const clip = (value: string) => value.length > 8 ? value.slice(0, 8) + '…' : value
  if (keys.length === 1) return clip(keys[0])
  return clip(keys[0]) + (keys.length === 2 ? ' · ' : ' … ') + clip(keys[keys.length - 1])
}

function BTreeCanvas({ snapshot, selectedPage, onNode }: {
  snapshot: BTreeSnapshot; selectedPage: number | null; onNode: (node: BTreeNode, trigger: SVGGElement) => void
}) {
  const { positioned, parents, positions, width, height, y } = useMemo(() => layoutTree(snapshot), [snapshot])
  const canvas = useRef<HTMLDivElement>(null)
  const rootX = positioned.get(snapshot.root)?.x
  useLayoutEffect(() => {
    if (canvas.current && rootX !== undefined) {
      canvas.current.scrollLeft = Math.max(0, rootX - canvas.current.clientWidth / 2)
    }
  }, [snapshot, rootX])

  const selectedPath = new Set<number>()
  for (let page = selectedPage ?? undefined; page !== undefined; page = parents.get(page)) selectedPath.add(page)
  const branches = positions.flatMap((parent) => parent.children.map((child) => ({ parent, child })))
  const drawBranch = ({ parent, child }: typeof branches[number]) => {
    const from = y(parent.depth) + NODE_HEIGHT
    const middle = from + VERTICAL_GAP / 2
    return <path key={parent.node.pageId + '-' + child.node.pageId} className="index-branch"
      data-selected={selectedPath.has(child.node.pageId)}
      d={'M ' + parent.x + ' ' + from + ' V ' + middle + ' H ' + child.x + ' V ' + y(child.depth)} />
  }

  if (!positions.length) return <div className="index-empty"><p>快照中没有可显示的节点。</p></div>

  return (
    <div ref={canvas} className="index-canvas" role="region" aria-label="B+ 树画布" tabIndex={0}>
      <svg className="index-tree-svg" width={width} height={height} viewBox={'0 0 ' + width + ' ' + height} role="group" aria-label="B+ 树结构">
        <defs>
          <marker id="index-leaf-arrow" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="5" markerHeight="5" orient="auto">
            <path d="M1 1 L5 3 L1 5" className="index-leaf-arrow" />
          </marker>
          <clipPath id="index-keys-clip"><rect x={12} y={27} width={NODE_WIDTH - 24} height={22} /></clipPath>
        </defs>
        {branches.filter(({ child }) => !selectedPath.has(child.node.pageId)).map(drawBranch)}
        {branches.filter(({ child }) => selectedPath.has(child.node.pageId)).map(drawBranch)}
        {positions.map(({ node, x, depth }) => {
          const next = node.kind === 'LEAF' ? positioned.get(node.next) : undefined
          if (!next || next.x <= x) return null
          return <path key={'leaf-' + node.pageId} className="index-leaf-link" data-leaf-from={node.pageId} data-leaf-to={next.node.pageId}
            d={'M ' + (x + NODE_WIDTH / 2) + ' ' + (y(depth) + NODE_HEIGHT / 2) + ' H ' + (next.x - NODE_WIDTH / 2 - 4)}
            markerEnd="url(#index-leaf-arrow)" />
        })}
        {positions.map(({ node, x, depth }) => {
          const root = node.pageId === snapshot.root
          const label = node.kind === 'LEAF' ? '叶节点' : '内部节点'
          return (
            <g key={node.pageId} className="index-node" transform={'translate(' + (x - NODE_WIDTH / 2) + ' ' + y(depth) + ')'}
              data-page-id={node.pageId} data-kind={node.kind} data-root={root} data-selected={selectedPage === node.pageId} role="button" tabIndex={0}
              aria-label={'查看页 ' + node.pageId + '（' + label + '）'}
              onClick={(event) => onNode(node, event.currentTarget)}
              onKeyDown={(event) => {
                if (event.key !== 'Enter' && event.key !== ' ') return
                event.preventDefault()
                if (!event.repeat) onNode(node, event.currentTarget)
              }}>
              <rect width={NODE_WIDTH} height={NODE_HEIGHT} rx={10} className="index-node-box" />
              <text x={12} y={20} className="index-node-type">{root ? '根节点 · ' : ''}{label}</text>
              <text x={NODE_WIDTH - 12} y={20} textAnchor="end" className="index-node-page">页 {node.pageId}</text>
              <text x={12} y={42} className="index-node-keys" clipPath="url(#index-keys-clip)">{previewKeys(node.keys)}</text>
              <text x={NODE_WIDTH - 12} y={55} textAnchor="end" className="index-node-page">{node.keys.length} 键</text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function NodeModal({ node, onClose }: { node: BTreeNode; onClose: () => void }) {
  return (
    <Modal title={'节点 · 页 ' + node.pageId + '（' + node.kind + '）'} onClose={onClose}>
      <div className="kv">
        <div className="item"><div className="k">页号</div><div className="v">{node.pageId}</div></div>
        <div className="item"><div className="k">键数</div><div className="v">{node.keys.length}</div></div>
        <div className="item"><div className="k">使用率</div><div className="v">{((node.usedBytes / node.totalBytes) * 100).toFixed(0)}%</div></div>
        <div className="item"><div className="k">占用字节</div><div className="v">{node.usedBytes}/{node.totalBytes}</div></div>
      </div>
      <h3>键</h3><pre className="code">{node.keys.length ? node.keys.join('  |  ') : '（空）'}</pre>
      {node.rids && <><h3>行 RID</h3><pre className="code">{node.rids.join('  |  ') || '（空）'}</pre></>}
      {node.children && <><h3>子节点页</h3><pre className="code">{node.children.join(', ')}</pre></>}
      {node.kind === 'LEAF' && <p className="index-create-note">叶链 · 前页 {node.prev || '∅'} · 后页 {node.next || '∅'}</p>}
      <div className="modal-actions"><button className="btn" type="button" autoFocus onClick={onClose}>关闭详情</button></div>
    </Modal>
  )
}

interface PlanRequest {
  sql: string
  indexRevision: number
}

function ExecutionPlan({ indexRevision }: { indexRevision: number }) {
  const [draft, setDraft] = useState('SELECT name FROM student WHERE id = 1;')
  const [request, setRequest] = useState<PlanRequest | null>(null)
  const [response, setResponse] = useState<{ request: PlanRequest; result: ReadResult<PlanResult> } | null>(null)

  useEffect(() => {
    if (!request) return
    let current = true
    void api.plan(request.sql).then((data) => {
      if (current) setResponse({ request, result: { data } })
    }, (error: unknown) => {
      if (current) setResponse({ request, result: { error: readError(error) } })
    })
    return () => { current = false }
  }, [request])

  const sql = draft.trim()
  const isCurrent = request?.sql === sql && request.indexRevision === indexRevision
  const result = isCurrent && response?.request === request ? response.result : null
  const pending = isCurrent && !result
  const plan = result && 'data' in result ? result.data : null

  return (
    <section className="card index-plan">
      <header className="index-plan-head">
        <div><h2><StudioIcon name="terminal" />执行计划</h2><p>输入 SELECT 查询，查看优化器选择的算子与层级。</p></div>
      </header>
      <form className="index-query" onSubmit={(event) => {
        event.preventDefault()
        if (sql && !pending) setRequest({ sql, indexRevision })
      }}>
        <label><StudioIcon name="terminal" /><input type="text" aria-label="用于查看执行计划的 SQL" value={draft}
          onChange={(event) => setDraft(event.target.value)} /></label>
        <button className="btn primary" type="submit" disabled={!sql || pending}><StudioIcon name="play" />查看计划</button>
      </form>
      {pending && <p className="index-state index-plan-state" role="status">正在生成执行计划…</p>}
      {result && 'error' in result && <div className="index-read-error" role="alert"><ErrorBox error={result.error} /></div>}
      {!pending && !result && (
        <p className="index-state index-plan-state">{request ? '请重新查看当前 SQL 的执行计划。' : '提交 SELECT 查询后在这里查看执行计划。'}</p>
      )}
      {plan && (
        <div className="index-plan-body">
          <section className="index-ops" aria-label="算子树"><h3>算子树</h3><OperatorTree nodes={plan.tree} /></section>
          <section className="index-raw" aria-label="计划原文"><h3>计划原文</h3><pre className="mono" tabIndex={0}>{plan.text}</pre></section>
        </div>
      )}
    </section>
  )
}

function OperatorTree({ nodes }: { nodes: PlanNode[] }) {
  return (
    <ul>
      {nodes.map((node, index) => (
        <li key={node.op + '-' + index}>
          <div className="index-op mono"><StudioIcon name="layers" /><strong>{node.op}</strong></div>
          {!!node.children.length && <OperatorTree nodes={node.children} />}
        </li>
      ))}
    </ul>
  )
}
