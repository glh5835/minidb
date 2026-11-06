import React, { useEffect, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox, Modal, exportCsv } from '../../components/ui'
import { StudioIcon } from '../../components/StudioIcon'
import { useApp } from '../../stores/app'
import type { ColumnInfo, RowsPage, SchemaInfo, TableInfo } from '../../types'
import './DatabasePage.css'

const PAGE_SIZE = 50
const emptyFilter = { column: '', op: '=', value: '' }
const tabs = [
  { id: 'data', label: '数据', icon: 'grid' },
  { id: 'schema', label: '结构', icon: 'layers' },
  { id: 'index', label: '索引', icon: 'tree' },
] as const

type Tab = typeof tabs[number]['id']
type Filter = typeof emptyFilter

interface Query {
  offset: number
  sort: { col: string; dir: 'asc' | 'desc' } | null
  filter: Filter
}

type EditState =
  | { mode: 'insert'; values: Record<string, string> }
  | { mode: 'edit'; rid: string; values: Record<string, string> }

type ReadResult<T> = { data: T } | { error: ApiFailure | string }

function readError(error: unknown): ApiFailure | string {
  return error instanceof ApiFailure ? error : '读取失败，请重试。'
}

function DatabaseEmpty({ message, children }: { message: string; children?: React.ReactNode }) {
  return (
    <div className="data-empty">
      <span className="data-table-icon"><StudioIcon name="grid" /></span>
      <p>{message}</p>
      {children}
    </div>
  )
}

export default function DatabasePage() {
  const { ready, session } = useApp()

  return (
    <section className="data-view">
      <header className="data-title">
        <h1>数据浏览</h1>
        <p>浏览当前库的表数据、字段结构和索引。</p>
      </header>
      {!ready ? <p role="status">正在连接 MiniDB…</p> : session?.hasDb ? (
        <DatabaseWorkspace key={session.sessionId + '/' + session.dbPath} path={session.dbPath ?? ''} />
      ) : (
        <div className="card data-detail">
          <DatabaseEmpty message="尚未打开数据库">
            <a className="btn primary" href="#/start">去开始页创建 / 打开数据库</a>
          </DatabaseEmpty>
        </div>
      )}
    </section>
  )
}

function DatabaseWorkspace({ path }: { path: string }) {
  const { tables } = useApp()
  const [selected, setSelected] = useState('')
  const [tab, setTab] = useState<Tab>('data')
  const table = tables.find((candidate) => candidate.name === selected) ?? tables[0]

  return (
    <>
    <div className="data-layout">
      <aside className="card data-objects" aria-label="数据库表">
        <div className="data-objects-top">
          <h2>表对象</h2>
          <a className="btn" href="#/sql" title="在 SQL 工作台建表"><StudioIcon name="plus" />建表</a>
        </div>
        <div className="data-db-context">
          <StudioIcon name="database" />
          <span className="mono" title={path}>{path.split(/[\\/]/).pop()}</span>
          <span>{tables.length} 张表</span>
        </div>
        {tables.map((item) => (
          <button
            key={item.name}
            className="data-object"
            type="button"
            aria-current={table?.name === item.name ? true : undefined}
            onClick={() => setSelected(item.name)}
          >
            <StudioIcon name="grid" />
            <span className="data-object-name mono" title={item.name}>{item.name}</span>
            <span>{item.rowCount} 行</span>
          </button>
        ))}
      </aside>
      {table ? <TableDetail key={table.name} table={table} tab={tab} setTab={setTab} /> : (
        <div className="card data-detail">
          <DatabaseEmpty message="暂无表。在 SQL 工作台执行 CREATE TABLE 创建第一张表。">
            <a className="btn primary" href="#/sql">打开 SQL 工作台</a>
          </DatabaseEmpty>
        </div>
      )}
    </div>
    {table && tab === 'data' && (
      <div className="data-note"><StudioIcon name="info" /><span>分页由 MiniDB 内核处理。编辑和删除使用 RID（页号 / 槽号）定位记录。</span></div>
    )}
    </>
  )
}

function TableDetail({ table, tab, setTab }: { table: TableInfo; tab: Tab; setTab: (tab: Tab) => void }) {
  const { session, confirm, notify, refresh } = useApp()
  const [schemaResult, setSchemaResult] = useState<ReadResult<SchemaInfo> | null>(null)
  const [schemaRevision, setSchemaRevision] = useState(0)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    void api.schema(table.name).then((data) => {
      if (current) setSchemaResult({ data })
    }, (error: unknown) => {
      if (current) setSchemaResult({ error: readError(error) })
    })
    return () => { current = false }
  }, [table.name, schemaRevision])

  const schema = schemaResult && 'data' in schemaResult ? schemaResult.data : null
  const reloadSchema = () => {
    setSchemaResult(null)
    setSchemaRevision((previous) => previous + 1)
  }

  const drop = async () => {
    setBusy(true)
    try {
      if (!await confirm({
        title: '删除表',
        message: '确定删除 ' + table.name + ' 表？表内全部数据与索引都会被删除，且 DDL 不走事务日志、无法回滚。',
        actionText: '删除 ' + table.name + ' 表',
      })) return
      await api.dropTable(table.name)
      notify('表 ' + table.name + ' 已删除', 'success')
      await refresh()
    } catch (error) {
      notify(error instanceof ApiFailure ? error.message : '删除表失败。', 'error')
    } finally {
      setBusy(false)
    }
  }

  const transactionActive = session?.txn.active ?? false

  return (
    <section className="card data-detail" aria-label={table.name + ' 表'}>
      <header className="data-detail-head">
        <div className="data-ident">
          <span className="data-table-icon"><StudioIcon name="grid" /></span>
          <div className="data-table-label">
            <h2><span className="mono" title={table.name}>{table.name}</span><span className="data-type-badge">{table.fixedLength ? '定长表' : '变长表'}</span></h2>
            <p>{schema && schema.columns.length + ' 列 · '}{table.indexes.length} 个索引</p>
          </div>
        </div>
        {tab === 'data' && (
          <button
            className="btn data-delete-table" type="button" disabled={busy || transactionActive}
            title={transactionActive ? '请提交或回滚当前事务后再删除表' : undefined}
            onClick={drop}
          >删除表</button>
        )}
      </header>

      <div className="data-tabs" role="tablist" aria-label="表视图">
        {tabs.map(({ id, label, icon }, index) => (
          <button
            key={id} type="button" className="data-tab" role="tab" id={'data-tab-' + id}
            aria-selected={tab === id} tabIndex={tab === id ? 0 : -1}
            aria-controls={'data-panel-' + id} onClick={() => setTab(id)}
            onKeyDown={(event) => {
              const { key } = event
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(key)) return
              event.preventDefault()
              const next = key === 'Home' ? 0 : key === 'End' ? tabs.length - 1 :
                (index + (key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
              const nextId = tabs[next].id
              setTab(nextId)
              event.currentTarget.parentElement?.querySelector<HTMLButtonElement>('#data-tab-' + nextId)?.focus()
            }}
          ><StudioIcon name={icon} />{label}</button>
        ))}
      </div>

      {tabs.map(({ id }) => (
        <div key={id} id={'data-panel-' + id} role="tabpanel" aria-labelledby={'data-tab-' + id} hidden={id !== tab}>
          {id === tab && (id === 'index' ? <IndexView table={table} /> : (
            <>
              {schemaResult && 'error' in schemaResult && (
                <div className="data-error" role="alert">
                  <ErrorBox error={schemaResult.error} />
                  <button className="btn small" type="button" onClick={reloadSchema}>重试读取结构</button>
                </div>
              )}
              {id === 'data' && <DataView table={table.name} schema={schema} reloadSchema={reloadSchema} busy={busy} setBusy={setBusy} />}
              {id === 'schema' && (schema ? <SchemaView schema={schema} /> : !schemaResult && <p className="data-loading" role="status">正在读取表结构…</p>)}
            </>
          ))}
        </div>
      ))}
    </section>
  )
}

function DataView({ table, schema, reloadSchema, busy, setBusy }: {
  table: string; schema: SchemaInfo | null; reloadSchema: () => void; busy: boolean; setBusy: (busy: boolean) => void
}) {
  const { session, refresh, confirm, notify } = useApp()
  const [query, setQuery] = useState<Query>({ offset: 0, sort: null, filter: emptyFilter })
  const [draft, setDraft] = useState(emptyFilter)
  const [response, setResponse] = useState<{ query: Query; result: ReadResult<RowsPage> } | null>(null)
  const [edit, setEdit] = useState<EditState | null>(null)

  useEffect(() => {
    let current = true
    const { offset, sort, filter } = query
    void api.rows(table, {
      offset, limit: PAGE_SIZE + 1,
      sort: sort?.col, dir: sort?.dir,
      filterColumn: filter.column, filterOp: filter.op, filterValue: filter.value,
    }).then((data) => {
      if (!current) return
      if (offset > 0 && data.rows.length === 0) {
        const lastPossibleOffset = Math.floor(Math.max(0, data.total - 1) / PAGE_SIZE) * PAGE_SIZE
        setQuery((latest) => latest === query ? { ...latest, offset: Math.min(offset - PAGE_SIZE, lastPossibleOffset) } : latest)
        return
      }
      setResponse({ query, result: { data } })
    }, (error: unknown) => {
      if (current) setResponse({ query, result: { error: readError(error) } })
    })
    return () => { current = false }
  }, [table, query])

  const result = response?.query === query ? response.result : null
  const page = result && 'data' in result ? result.data : null
  const rows = page?.rows.slice(0, PAGE_SIZE) ?? []
  const hasNext = (page?.rows.length ?? 0) > PAGE_SIZE
  const columns = schema?.columns ?? []

  const reload = () => setQuery((previous) => ({ ...previous }))
  const applyFilter = (filter: Filter) => setQuery((previous) => ({ ...previous, offset: 0, filter }))

  const startEdit = (row?: Record<string, unknown>) => {
    const values = Object.fromEntries(columns.map((column) => [column.name, String(row?.[column.name] ?? '')]))
    setEdit(row ? { mode: 'edit', rid: String(row._rid), values } : { mode: 'insert', values })
  }

  const mutationError = (error: unknown) => {
    if (error instanceof ApiFailure && error.code === 'RID_STALE') {
      notify('该记录已发生变化，请刷新后重试。', 'error')
    } else {
      notify(error instanceof ApiFailure ? error.message : '记录操作失败。', 'error')
    }
  }

  const deleteRow = async (rid: string) => {
    setBusy(true)
    try {
      if (!await confirm({
        title: '删除记录',
        message: '确定删除 ' + table + ' 中 RID 为 ' + rid + ' 的记录？此操作按 RID 精确定位。',
        actionText: '删除记录',
      })) return
      await api.deleteRow(table, rid)
      notify('记录已删除', 'success')
      reload()
      await refresh()
    } catch (error) {
      mutationError(error)
    } finally {
      setBusy(false)
    }
  }

  const save = async () => {
    if (!edit || busy) return
    setBusy(true)
    try {
      const values = Object.fromEntries(columns.map((column) => {
        const raw = edit.values[column.name] ?? ''
        // 由 API 按列解析，避免 JS 浮点舍入和无效数字被序列化为 null。
        return [column.name, raw === '' ? null : raw]
      }))
      if (edit.mode === 'insert') {
        await api.insertRow(table, values)
        notify('记录已添加', 'success')
      } else {
        const updated = await api.updateRow(table, edit.rid, values)
        notify(updated.migrated ? '记录已更新，RID 迁移 ' + updated.oldRid + ' → ' + updated.newRid : '记录已更新', 'success')
      }
      setEdit(null)
      reload()
      await refresh()
    } catch (error) {
      mutationError(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div aria-busy={!result || busy}>
      <div className="data-toolbar">
        <form className="data-filters" aria-label="记录筛选" onSubmit={(event) => {
          event.preventDefault()
          if (!busy && schema && draft.column) applyFilter(draft)
        }}>
          <select aria-label="筛选列" value={draft.column} disabled={busy || !schema}
            onChange={(event) => setDraft({ ...draft, column: event.target.value })}>
            <option value="">筛选列…</option>
            {columns.map((column) => <option key={column.name} value={column.name}>{column.name}</option>)}
          </select>
          <select className="data-comparison" aria-label="比较运算符" value={draft.op} disabled={busy || !schema || !draft.column}
            onChange={(event) => setDraft({ ...draft, op: event.target.value })}>
            {['=', '!=', '<', '<=', '>', '>=', 'LIKE'].map((op) => <option key={op}>{op}</option>)}
          </select>
          <input type="text" aria-label="筛选值" placeholder="筛选值" value={draft.value} disabled={busy || !schema || !draft.column}
            onChange={(event) => setDraft({ ...draft, value: event.target.value })} />
          <button className="btn" type="submit" disabled={busy || !schema || !draft.column}><StudioIcon name="search" />筛选</button>
          <button className="btn" type="button" disabled={busy || (!draft.column && !query.filter.column)} onClick={() => {
            setDraft(emptyFilter)
            applyFilter(emptyFilter)
          }}>清除</button>
        </form>
        <div className="data-actions">
          <button className="btn" type="button" disabled={busy} onClick={() => { reloadSchema(); reload(); void refresh() }}>
            <StudioIcon name="refresh" />刷新
          </button>
          <button className="btn" type="button" disabled={busy || !schema || !page || !rows.length} onClick={() => {
            exportCsv(columns.map((column) => column.name), rows.map((row) => columns.map((column) => row[column.name])), table + '.csv')
          }}><StudioIcon name="download" />导出当前页</button>
          <button className="btn primary" type="button" disabled={busy || !schema} onClick={() => startEdit()}>
            <StudioIcon name="plus" />添加记录
          </button>
        </div>
      </div>

      {!result && <p className="data-loading" role="status">正在读取记录…</p>}
      {result && 'error' in result && (
        <div className="data-error" role="alert">
          <ErrorBox error={result.error} />
          <button className="btn small" type="button" onClick={reload}>重试读取记录</button>
        </div>
      )}
      {page && schema && (rows.length ? (
        <>
          <div className="data-scroll" tabIndex={0} role="region" aria-label="记录表格">
            <table className="data-grid" aria-label={table + ' 数据记录'}>
              <thead>
                <tr>
                  <th scope="col" className="mono">RID</th>
                  {columns.map((column) => {
                    const direction = query.sort?.col === column.name ? query.sort.dir : undefined
                    return (
                      <th key={column.name} scope="col" aria-sort={direction === 'asc' ? 'ascending' : direction === 'desc' ? 'descending' : undefined}>
                        <button className="data-sort" type="button" disabled={busy} onClick={() => setQuery((previous) => ({
                          ...previous, offset: 0,
                          sort: { col: column.name, dir: direction === 'asc' ? 'desc' : 'asc' },
                        }))}>
                          <span className="mono">{column.name}</span><ColumnType column={column} />
                          {direction && <span className="data-sort-direction" aria-hidden>{direction === 'asc' ? '▲' : '▼'}</span>}
                        </button>
                      </th>
                    )
                  })}
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const rid = String(row._rid)
                  return (
                    <tr key={rid}>
                      <td className="rid mono">{rid}</td>
                      {columns.map((column) => (
                        <td key={column.name} className={column.type === 'VARCHAR' ? undefined : 'mono'}>
                          {row[column.name] == null ? <span className="muted">NULL</span> : String(row[column.name])}
                        </td>
                      ))}
                      <td>
                        <div className="data-cell-actions">
                          <button className="data-row-action" type="button" disabled={busy} onClick={() => startEdit(row)}><StudioIcon name="edit" />编辑</button>
                          <button className="data-row-action data-danger" type="button" disabled={busy} onClick={() => deleteRow(rid)}>删除</button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <footer className="data-footer">
            <div>
              <span>{query.filter.column ? '匹配' : ''}第 {page.offset + 1}–{page.offset + rows.length} 条</span>
              {!query.filter.column && <span> · 共 {page.total} 条</span>}
              <span className="data-elapsed"> · 本次查询 {page.meta.elapsedMs} ms</span>
            </div>
            <div className="data-page-control" aria-label="结果分页">
              <button className="btn" type="button" disabled={busy || page.offset === 0}
                onClick={() => setQuery((previous) => ({ ...previous, offset: Math.max(0, page.offset - PAGE_SIZE) }))}>上一页</button>
              <span className="data-page-number" aria-current="page">{Math.floor(page.offset / PAGE_SIZE) + 1}</span>
              <button className="btn" type="button" disabled={busy || !hasNext}
                onClick={() => setQuery((previous) => ({ ...previous, offset: page.offset + PAGE_SIZE }))}>下一页</button>
            </div>
          </footer>
        </>
      ) : (
        <DatabaseEmpty message={query.filter.column ? '没有匹配当前条件的记录。' : '暂无数据'}>
          {!query.filter.column && <button className="btn primary" type="button" disabled={busy} onClick={() => startEdit()}>添加第一条记录</button>}
        </DatabaseEmpty>
      ))}

      {edit && schema && (
        <Modal title={edit.mode === 'insert' ? '在 ' + table + ' 中添加记录' : '编辑 ' + table + ' 记录（RID ' + edit.rid + '）'}
          onClose={() => { if (!busy) setEdit(null) }}>
          <form onSubmit={(event) => { event.preventDefault(); void save() }}>
            <div className="data-edit-fields">
              {columns.map((column, index) => (
                <label key={column.name} className="data-edit-field">
                  <span>{column.name} <ColumnType column={column} /></span>
                  <input type="text" autoFocus={index === 0} value={edit.values[column.name] ?? ''} disabled={busy}
                    placeholder={column.type === 'BIGINT' ? '整数（可超 JS 精度）' : undefined}
                    onChange={(event) => setEdit({ ...edit, values: { ...edit.values, [column.name]: event.target.value } })} />
                </label>
              ))}
            </div>
            <p className="data-modal-help">留空会使用字段类型的默认值。
              {session?.txn.active && ' 当前事务中的修改将随事务提交或回滚。'}
            </p>
            <div className="modal-actions">
              <button className="btn" type="button" disabled={busy} onClick={() => setEdit(null)}>取消</button>
              <button className="btn primary" type="submit" disabled={busy}>{edit.mode === 'insert' ? '添加' : '保存'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

function ColumnType({ column }: { column: ColumnInfo }) {
  return <span className="data-column-type mono">{column.type}{column.maxLength ? '(' + column.maxLength + ')' : ''}</span>
}

function SchemaView({ schema }: { schema: SchemaInfo }) {
  return (
    <div className="data-secondary">
      <h3>{schema.table} 的结构</h3>
      <div className="data-scroll" role="region" tabIndex={0} aria-label="字段表格">
        <table className="data-grid" aria-label={schema.table + ' 字段'}>
          <thead><tr><th scope="col">字段</th><th scope="col">类型</th><th scope="col">长度</th><th scope="col">定长</th></tr></thead>
          <tbody>
            {schema.columns.map((column) => (
              <tr key={column.name}>
                <td className="mono">{column.name}</td><td className="mono">{column.type}</td><td>{column.maxLength ?? '—'}</td><td>{column.fixed ? '是' : '否'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>记录格式：{schema.fixedLength ? '定长记录（' + schema.fixedRecordSize + ' 字节/行）' : '变长记录（含 VARCHAR 列）'}。
        MiniDB 支持 INT / BIGINT / DOUBLE / VARCHAR(n≤3000)，列的唯一性由唯一索引保证。</p>
    </div>
  )
}

function IndexView({ table }: { table: TableInfo }) {
  return (
    <div className="data-secondary">
      <div className="data-secondary-head">
        <h3>{table.name} 的索引</h3>
        <a className="btn" href="#/index"><StudioIcon name="tree" />索引与计划</a>
      </div>
      {table.indexes.length ? (
        <div className="data-scroll" role="region" tabIndex={0} aria-label="索引表格">
          <table className="data-grid" aria-label={table.name + ' 索引'}>
            <thead><tr><th scope="col">索引</th><th scope="col">列</th><th scope="col">键类型</th><th scope="col">唯一</th></tr></thead>
            <tbody>
              {table.indexes.map((index) => (
                <tr key={index.name}><td className="mono">{index.name}</td><td>{index.column}</td><td>{index.keyType}</td><td>是</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <p>该表暂无索引。可在「索引与计划」页创建。</p>}
    </div>
  )
}
