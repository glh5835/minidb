import React, { useCallback, useEffect, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'
import { StudioIcon } from '../../components/StudioIcon'
import { useApp } from '../../stores/app'
import './StorageLab.css'

type AnyMap = Record<string, unknown>
type ExperimentView<T> =
  | { kind: 'loading' | 'uninitialized' }
  | { kind: 'error'; error: ApiFailure | string }
  | { kind: 'ready'; snapshot: T }

const sections = [
  { id: 'records', label: '页与记录', icon: 'layers' },
  { id: 'pool', label: '缓冲池', icon: 'chip' },
] as const
type Section = typeof sections[number]['id']

const pageTypeLabels: Record<string, string> = {
  FREE: '空闲页',
  META: '元数据页',
  BITMAP: '位图页',
  FIXED_TABLE: '定长数据页',
  VAR_TABLE: '变长数据页',
  CATALOG: '目录页',
  BTREE_INTERNAL: '索引内部页',
  BTREE_LEAF: '索引叶页',
}

function experimentFailure(error: unknown): ExperimentView<never> {
  if (error instanceof ApiFailure && error.code === 'EXPERIMENT_NOT_READY') return { kind: 'uninitialized' }
  return { kind: 'error', error: error instanceof ApiFailure ? error : '无法读取实验状态，请重试。' }
}

function useExperiment<T>(read: () => Promise<T>, label: string) {
  const { confirm } = useApp()
  const [view, setView] = useState<ExperimentView<T>>({ kind: 'loading' })
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    setView({ kind: 'loading' })
    void read().then((snapshot) => {
      if (current) setView({ kind: 'ready', snapshot })
    }, (error: unknown) => {
      if (current) setView(experimentFailure(error))
    })
    return () => { current = false }
  }, [read, revision])

  const reload = () => {
    setView({ kind: 'loading' })
    setRevision((previous) => previous + 1)
  }

  const update = async (action: () => Promise<T>, needsResetConfirmation = false) => {
    setBusy(true)
    try {
      if (needsResetConfirmation && view.kind === 'ready' && !await confirm({
        title: '重置' + label + '实验库',
        message: '此操作会重建' + label + '实验库，清除当前实验数据和状态。工作库不受影响。',
        actionText: '确认重置',
      })) return
      setView({ kind: 'ready', snapshot: await action() })
    } catch (error) {
      setView(experimentFailure(error))
    } finally {
      setBusy(false)
    }
  }

  return { view, busy, reload, update }
}

export default function StorageLab() {
  const [section, setSection] = useState<Section>('pool')

  return (
    <div className="storage-view">
      <header className="storage-head">
        <div className="storage-title">
          <h1>存储与缓冲池</h1>
          <span className="storage-tag"><StudioIcon name="flask" />独立实验库</span>
        </div>
        <p>观察 4 KB 数据页，以及读取、更新和刷盘如何改变缓冲池状态。</p>
      </header>

      <div className="storage-tabs" role="tablist" aria-label="实验分区">
        {sections.map(({ id, label, icon }) => (
          <button
            key={id}
            type="button"
            id={'storage-tab-' + id}
            role="tab"
            aria-selected={section === id}
            aria-controls={'storage-panel-' + id}
            tabIndex={section === id ? 0 : -1}
            onClick={() => setSection(id)}
            onKeyDown={(event) => {
              const { key } = event
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(key)) return
              event.preventDefault()
              const next = key === 'Home' ? 'records' : key === 'End' ? 'pool' : id === 'pool' ? 'records' : 'pool'
              setSection(next)
              event.currentTarget.parentElement?.querySelector<HTMLButtonElement>('#storage-tab-' + next)?.focus()
            }}
          >
            <StudioIcon name={icon} />{label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id="storage-panel-records" aria-labelledby="storage-tab-records" hidden={section !== 'records'}>
        <StorageSection />
      </div>
      <div role="tabpanel" id="storage-panel-pool" aria-labelledby="storage-tab-pool" hidden={section !== 'pool'}>
        <BufferPoolSection />
      </div>
    </div>
  )
}

function ExperimentStatus({ view, label, busy, setup, reload }: {
  view: Exclude<ExperimentView<unknown>, { kind: 'ready' }>
  label: string
  busy: boolean
  setup: () => void
  reload: () => void
}) {
  if (view.kind === 'loading') return <div className="card storage-empty" role="status">正在读取{label}实验状态…</div>
  if (view.kind === 'error') return (
    <div className="card storage-empty">
      <div role="alert"><ErrorBox error={view.error} /></div>
      <button className="btn" type="button" onClick={reload}>重试读取</button>
    </div>
  )
  return (
    <div className="card storage-empty">
      <span className="storage-icon"><StudioIcon name="flask" /></span>
      <h2>{label}实验尚未初始化</h2>
      <p>初始化独立实验库后即可操作。</p>
      <button className="btn primary" type="button" onClick={setup} disabled={busy}>
        <StudioIcon name="play" />{busy ? '正在初始化…' : '初始化实验库'}
      </button>
    </div>
  )
}

function ResetButton({ busy, onClick }: { busy: boolean; onClick: () => void }) {
  return (
    <button className="btn storage-reset" type="button" onClick={onClick} disabled={busy}>
      <StudioIcon name="refresh" />重置实验库
    </button>
  )
}

function StorageSection() {
  const [table, setTable] = useState('fixed_demo')
  const [note, setNote] = useState('')
  const read = useCallback(() => api.lab.storageState(table), [table])
  const { view, busy, reload, update } = useExperiment(read, '页与记录')
  const setup = () => update(async () => {
    const initial = await api.lab.storageSetup()
    const snapshot = table === 'fixed_demo' ? initial : await read()
    setNote('实验库已初始化，包含 fixed_demo 和 var_demo。')
    return snapshot
  }, true)
  const act = (body: AnyMap) => update(async () => {
    const result = await api.lab.storageAct({ table, ...body })
    setNote(String(result.result ?? ''))
    return result.state as AnyMap
  })

  if (view.kind !== 'ready') {
    return <ExperimentStatus view={view} label="页与记录" busy={busy} setup={setup} reload={reload} />
  }

  const state = view.snapshot
  const pages = (state.pages as AnyMap[]) ?? []
  const rows = (state.rows as AnyMap[]) ?? []
  const columns = rows.length ? Object.keys(rows[0]) : []
  const pageSize = Number(state.pageSize)

  return (
    <div className="storage-panel" aria-busy={busy}>
      <div className="storage-toolbar">
        <div className="storage-actions">
          <label className="storage-table-select">表
            <select value={table} disabled={busy} onChange={(e) => {
              setNote('')
              setTable(e.target.value)
              reload()
            }}>
              <option value="fixed_demo">fixed_demo（定长）</option>
              <option value="var_demo">var_demo（变长）</option>
            </select>
          </label>
          <button className="btn primary" type="button" disabled={busy} onClick={() => act({ op: 'insertFixed', id: Date.now() % 1000, num: 42, score: 1 })}>
            <StudioIcon name="plus" />插入定长记录
          </button>
          <button className="btn" type="button" disabled={busy} onClick={() => act({ op: 'insertVar', id: Date.now() % 1000, note: '记录-' + (Date.now() % 10000) })}>
            <StudioIcon name="plus" />插入变长记录
          </button>
        </div>
        <ResetButton busy={busy} onClick={setup} />
      </div>
      <section className="card storage-records">
        <div className="storage-table-head">
          <div>
            <h2>页与记录概览</h2>
            <p>定长表用位图管理槽位，变长表用槽位数组管理记录。RID 由页号和槽号组成。</p>
          </div>
        </div>
        <dl className="storage-summary">
          <div><dt>行数</dt><dd>{String(state.rowCount)}</dd></div>
          <div><dt>页数</dt><dd>{String(state.pageCount)}</dd></div>
          <div><dt>总空闲字节</dt><dd>{String(state.totalFreeBytes)}</dd></div>
          <div><dt>记录格式</dt><dd>{String(state.fixedLength) === 'true' ? '定长' : '变长'}</dd></div>
        </dl>
      </section>
      {note && <div className="storage-note" role="status"><StudioIcon name="info" />{note}</div>}
      <section className="card storage-table-card">
        <div className="storage-table-head"><h2>页视图</h2></div>
        <div className="storage-table-scroll">
          <table aria-label="真实页头">
            <thead><tr><th>页号</th><th>类型</th><th>槽位数</th><th>已用字节</th><th>空闲字节</th><th>下一页</th><th>PageLSN</th></tr></thead>
            <tbody>
              {pages.map((page) => {
                const type = String(page.type)
                return (
                  <tr key={String(page.pageId)}>
                    <td className="mono">{String(page.pageId)}</td>
                    <td>{pageTypeLabels[type] ?? type}</td>
                    <td>{String(page.numSlots)}</td>
                    <td><div className="storage-used">
                      <span className="mono">{String(page.usedBytes)}</span>
                      <div className="storage-bar" aria-hidden="true"><span style={{ width: (Number(page.usedBytes) / pageSize) * 100 + '%' }} /></div>
                    </div></td>
                    <td className="mono">{String(page.totalFree)}</td>
                    <td className="mono">{String(page.next || '∅')}</td>
                    <td className="mono">{String(page.pageLsn ?? '—')}</td>
                  </tr>
                )
              })}
              {!pages.length && <tr><td colSpan={7}>当前表没有数据页。</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card storage-table-card">
        <div className="storage-table-head"><h2>行与 RID</h2></div>
        {columns.length ? (
          <div className="storage-table-scroll">
            <table aria-label="行与 RID">
              <thead><tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
              <tbody>
                {rows.map((row, index) => (
                  <tr key={String(row._rid ?? index)}>
                    {columns.map((column) => <td key={column} className={column === '_rid' ? 'mono' : undefined}>{String(row[column])}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p className="storage-no-rows">当前表没有记录。</p>}
      </section>
    </div>
  )
}

function PercentageBar({ label, value }: { label: string; value: number }) {
  return (
    <div className="storage-bar" role="meter" aria-label={label} aria-valuenow={value} aria-valuemin={0} aria-valuemax={100}>
      <span style={{ width: value + '%' }} />
    </div>
  )
}

const previewLimit = 5

function BufferPoolSection() {
  const { view, busy, reload, update } = useExperiment(api.lab.bpState, '缓冲池')
  const [expanded, setExpanded] = useState(false)

  const setup = () => update(async () => {
    const snapshot = await api.lab.bpSetup()
    setExpanded(false)
    return snapshot
  }, true)
  const act = (op: string, extra?: Record<string, unknown>) => update(() => api.lab.bpAct(op, extra))

  if (view.kind !== 'ready') {
    return <ExperimentStatus view={view} label="缓冲池" busy={busy} setup={setup} reload={reload} />
  }

  const snap = view.snapshot
  const occupancy = Number((snap.size * 100 / snap.capacity).toFixed(1))
  const samples = snap.hits + snap.misses
  const hitRate = samples ? Number((snap.hits * 100 / samples).toFixed(1)) : null
  const dirty = snap.dirtyPages ?? snap.pages.filter((page) => page.dirty).length
  const rows = expanded ? snap.pages : snap.pages.slice(0, previewLimit)

  return (
    <div className="storage-panel" aria-busy={busy}>
      <div className="storage-toolbar" aria-label="缓冲池操作">
        <div className="storage-actions">
          <button className="btn primary" type="button" disabled={busy} onClick={() => act('readAll')}><StudioIcon name="scan" />全表扫描</button>
          <button className="btn" type="button" disabled={busy} onClick={() => act('readOne', { id: 7 })}><StudioIcon name="search" />索引点查</button>
          <button className="btn" type="button" disabled={busy} onClick={() => act('update', { id: 3 })}><StudioIcon name="edit" />更新记录</button>
          <button className="btn" type="button" disabled={busy} onClick={() => act('flush')}><StudioIcon name="download" />刷盘</button>
        </div>
        <ResetButton busy={busy} onClick={setup} />
      </div>

      <div className="storage-metrics" aria-label="缓冲池指标">
        <section className="card storage-metric">
          <div className="storage-metric-label"><span className="storage-icon"><StudioIcon name="layers" /></span>缓存占用</div>
          <div className="storage-num">{snap.size} <small>/ {snap.capacity} 页</small></div>
          <div className="storage-detail"><span>已缓存页 / 容量</span><span>{occupancy}%</span></div>
          <PercentageBar label="缓存占用率" value={occupancy} />
        </section>
        <section className="card storage-metric">
          <div className="storage-metric-label"><span className="storage-icon"><StudioIcon name="activity" /></span>缓存命中率</div>
          <div className="storage-num">{hitRate === null ? '未采样' : <>{hitRate}<small>%</small></>}</div>
          <div className="storage-detail"><span>命中 {snap.hits} 次</span><span>未命中 {snap.misses} 次</span></div>
          {hitRate !== null && <PercentageBar label="缓存命中率" value={hitRate} />}
        </section>
        <section className="card storage-metric">
          <div className="storage-metric-label"><span className="storage-icon"><StudioIcon name="edit" /></span>脏页</div>
          <div className="storage-num">{dirty} <small>页</small></div>
          <div className="storage-detail">页内容已变更，等待写回</div>
        </section>
        <section className="card storage-metric">
          <div className="storage-metric-label"><span className="storage-icon"><StudioIcon name="download" /></span>累计写回</div>
          <div className="storage-num">{snap.writebacks} <small>次</small></div>
          <div className="storage-detail">本次实验累计写回磁盘</div>
        </section>
      </div>

      <section className="card storage-table-card">
        <div className="storage-table-head">
          <div><h2>缓存页明细</h2><p>按 LRU 访问顺序展示，从最久未访问到最近访问。</p></div>
          <span className="storage-count">{snap.size} 页已缓存</span>
        </div>
        <div className="storage-table-scroll">
          <table aria-label="缓存页明细" id="storage-cache-pages">
            <thead><tr><th>页号</th><th>页类型</th><th>Pin 计数</th><th>修改状态</th><th>LRU 顺位</th><th>PageLSN</th></tr></thead>
            <tbody>
              {rows.map((page, index) => (
                <tr key={page.pageId}>
                  <td className="mono">{page.pageId}</td>
                  <td>{pageTypeLabels[page.type] ?? page.type}</td>
                  <td className="mono">{page.pin}</td>
                  <td><span className={'storage-state' + (page.dirty ? ' dirty' : '')}>{page.dirty ? '脏页' : '干净'}</span></td>
                  <td className="mono">{String(index + 1).padStart(2, '0')}</td>
                  <td className="mono">{page.pageLsn ?? '—'}</td>
                </tr>
              ))}
              {!rows.length && <tr><td colSpan={6}>缓冲池中暂无缓存页。</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="storage-table-foot">
          <span>显示 {rows.length} / {snap.pages.length} 页 <span className="storage-secondary-counts">· 淘汰 {snap.evictions} 次 · 磁盘 {snap.diskPages} 页</span></span>
          {snap.pages.length > previewLimit && (
            <button className="storage-link" type="button" aria-controls="storage-cache-pages" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起明细' : '展开全部 ' + snap.pages.length + ' 页'}<StudioIcon name="chevron" />
            </button>
          )}
        </div>
      </section>

      <div className="storage-note" role="status">
        <StudioIcon name="info" />
        <strong>{snap.lastAction ? '最近操作' : '观察提示'}</strong>
        <span>{snap.lastAction ?? '更新记录后，相关页会标为脏页。刷盘会写回脏页，累计写回次数随之增加。'}</span>
      </div>
    </div>
  )
}
