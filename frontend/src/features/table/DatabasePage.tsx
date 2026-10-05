import React, { useCallback, useEffect, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { useApp } from '../../stores/app'
import { Empty, ErrorBox, Modal, Pager, exportCsv } from '../../components/ui'
import { nav } from '../../router'
import type { ColumnInfo, RowsPage, SchemaInfo, TableInfo } from '../../types'

const PAGE_SIZE = 50

interface EditState {
  mode: 'insert' | 'edit'
  rid?: string
  values: Record<string, string>
}

/** 数据浏览器（计划书 §7/§8）：表列表 + 数据/结构/索引 三视图 */
export default function DatabasePage() {
  const { session, tables, refresh, confirm, notify } = useApp()
  const [table, setTable] = useState<string>('')
  const [tab, setTab] = useState<'data' | 'schema' | 'index'>('data')
  const [error, setError] = useState<ApiFailure | null>(null)

  useEffect(() => {
    if (!table && tables.length > 0) setTable(tables[0].name)
    if (table && !tables.some((t) => t.name === table)) setTable(tables[0]?.name ?? '')
  }, [tables, table])

  if (!session?.hasDb) {
    return (
      <div style={{ padding: 20 }}>
        <Empty icon="🗂️" message="尚未打开数据库">
          <button className="btn primary" onClick={() => nav('/start')}>去开始页创建 / 打开数据库</button>
        </Empty>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      <div style={{ width: 210, flexShrink: 0, borderRight: '1px solid var(--border)', padding: 16, overflowY: 'auto', background: 'var(--content)' }}>
        <div className="row" style={{ justifyContent: 'space-between', marginBottom: 10 }}>
          <h3 style={{ margin: 0 }}>表</h3>
          <button className="btn small primary" onClick={() => nav('/sql')} title="用 CREATE TABLE 建表">＋</button>
        </div>
        {tables.length === 0 && <div className="muted">暂无表</div>}
        {tables.map((t) => (
          <div key={t.name}
            onClick={() => setTable(t.name)}
            style={{
              padding: '6px 10px', borderRadius: 8, cursor: 'pointer', marginBottom: 2,
              background: table === t.name ? 'var(--primary-light)' : 'transparent',
              color: table === t.name ? 'var(--primary-dark)' : 'inherit',
              fontWeight: table === t.name ? 600 : 400,
            }}>
            {t.name}
            <span className="muted" style={{ fontSize: 12, marginLeft: 6 }}>{t.rowCount} 行</span>
          </div>
        ))}
      </div>
      <div style={{ flex: 1, minWidth: 0, padding: '20px 24px', overflow: 'auto' }}>
        {table ? (
          <>
            <div className="tabs">
              <button className={tab === 'data' ? 'active' : ''} onClick={() => setTab('data')}>数据</button>
              <button className={tab === 'schema' ? 'active' : ''} onClick={() => setTab('schema')}>结构</button>
              <button className={tab === 'index' ? 'active' : ''} onClick={() => setTab('index')}>索引</button>
            </div>
            {error && <ErrorBox error={error} />}
            {tab === 'data' && <DataView table={table} />}
            {tab === 'schema' && <SchemaView table={table} />}
            {tab === 'index' && <IndexMiniView table={table} />}
          </>
        ) : (
          <Empty icon="📋" message="暂无表。在 SQL 工作台执行 CREATE TABLE 创建第一张表。">
            <button className="btn primary" onClick={() => nav('/sql')}>打开 SQL 工作台</button>
          </Empty>
        )}
      </div>
    </div>
  )
}

// ---------------- 数据视图 ----------------

function DataView({ table }: { table: string }) {
  const { refresh, confirm, notify, session } = useApp()
  const [page, setPage] = useState<RowsPage | null>(null)
  const [schema, setSchema] = useState<SchemaInfo | null>(null)
  const [offset, setOffset] = useState(0)
  const [sort, setSort] = useState<{ col: string; dir: 'asc' | 'desc' } | null>(null)
  const [filter, setFilter] = useState({ column: '', op: '=', value: '' })
  const [edit, setEdit] = useState<EditState | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [p, s] = await Promise.all([
        api.rows(table, {
          offset, limit: PAGE_SIZE,
          sort: sort?.col, dir: sort?.dir,
          filterColumn: filter.column, filterOp: filter.op, filterValue: filter.value,
        }),
        api.schema(table),
      ])
      setPage(p)
      setSchema(s)
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setLoading(false)
    }
  }, [table, offset, sort, filter])

  useEffect(() => { load() }, [load])

  const columns = schema?.columns ?? []

  const doDelete = async (rid: string) => {
    if (!(await confirm({
      title: '删除记录',
      message: `确定删除 ${table} 中 RID 为 ${rid} 的记录？此操作按 RID 精确定位。`,
      actionText: '删除记录',
    }))) return
    try {
      await api.deleteRow(table, rid)
      notify('记录已删除', 'success')
      await load()
      await refresh()
    } catch (e) {
      if (e instanceof ApiFailure) {
        if (e.code === 'RID_STALE') notify('该记录已发生变化，请刷新后重试。', 'error')
        else notify(e.message, 'error')
      }
    }
  }

  const saveEdit = async () => {
    if (!edit) return
    const values: Record<string, unknown> = {}
    for (const col of columns) {
      const raw = edit.values[col.name] ?? ''
      values[col.name] = raw === '' ? null : convertValue(raw, col)
    }
    try {
      if (edit.mode === 'insert') {
        await api.insertRow(table, values)
        notify('记录已添加', 'success')
      } else {
        const r = await api.updateRow(table, edit.rid!, values)
        notify(r.migrated ? `记录已更新，RID 迁移 ${r.oldRid} → ${r.newRid}` : '记录已更新', 'success')
      }
      setEdit(null)
      await load()
      await refresh()
    } catch (e) {
      if (e instanceof ApiFailure) {
        if (e.code === 'RID_STALE') notify('该记录已发生变化，请刷新后重试。', 'error')
        else if (e.code === 'UNIQUE_CONSTRAINT_VIOLATION') notify(e.message, 'error')
        else notify(e.message, 'error')
      }
    }
  }

  return (
    <div>
      <div className="toolbar">
        <div className="row">
          <select value={filter.column} onChange={(e) => setFilter({ ...filter, column: e.target.value })}>
            <option value="">筛选列…</option>
            {columns.map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
          </select>
          <select value={filter.op} onChange={(e) => setFilter({ ...filter, op: e.target.value })} disabled={!filter.column}>
            {['=', '!=', '<', '<=', '>', '>=', 'LIKE'].map((o) => <option key={o}>{o}</option>)}
          </select>
          <input type="text" placeholder="值" style={{ width: 120 }} value={filter.value}
            disabled={!filter.column}
            onChange={(e) => setFilter({ ...filter, value: e.target.value })}
            onKeyDown={(e) => e.key === 'Enter' && setOffset(0)} />
          <button className="btn small" onClick={() => { setOffset(0); load() }}>筛选</button>
          <button className="btn small" onClick={() => setFilter({ column: '', op: '=', value: '' })}>清除</button>
        </div>
        <div className="grow" />
        <button className="btn small" onClick={() => load()}>↻ 刷新</button>
        <button className="btn small" onClick={() => {
          if (page && schema) exportCsv(
            schema.columns.map((c) => c.name),
            page.rows.map((r) => schema.columns.map((c) => r[c.name])),
            `${table}.csv`)
        }}>导出 CSV</button>
        <button className="btn small primary" onClick={() => {
          const v: Record<string, string> = {}
          columns.forEach((c) => (v[c.name] = ''))
          setEdit({ mode: 'insert', values: v })
        }}>＋ 添加记录</button>
        <button className="btn small danger" onClick={async () => {
          if (!(await confirm({
            title: '删除表',
            message: `确定删除 ${table} 表？表内全部数据与索引都会被删除，且 DDL 不走事务日志、无法回滚。`,
            actionText: `删除 ${table} 表`,
          }))) return
          try {
            await api.dropTable(table)
            notify(`表 ${table} 已删除`, 'success')
            await refresh()
          } catch (e) {
            if (e instanceof ApiFailure) notify(e.message, 'error')
          }
        }}>删除表</button>
      </div>

      {loading && !page ? <div className="empty-state">加载中…</div> :
        !page || page.rows.length === 0 ? (
          <Empty icon="📭" message="暂无数据">
            <button className="btn primary" onClick={() => {
              const v: Record<string, string> = {}
              columns.forEach((c) => (v[c.name] = ''))
              setEdit({ mode: 'insert', values: v })
            }}>＋ 添加第一条记录</button>
          </Empty>
        ) : (
          <>
            <div className="table-wrap">
              <table className="grid">
                <thead>
                  <tr>
                    <th className="rid">RID</th>
                    {columns.map((c) => (
                      <th key={c.name} style={{ cursor: 'pointer' }}
                        onClick={() => setSort((s) =>
                          s?.col === c.name ? { col: c.name, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { col: c.name, dir: 'asc' })}>
                        {c.name}
                        <span className="muted" style={{ fontWeight: 400 }}> {c.type}{c.maxLength ? `(${c.maxLength})` : ''}</span>
                        {sort?.col === c.name && (sort.dir === 'asc' ? ' ▲' : ' ▼')}
                      </th>
                    ))}
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.rows.map((r, i) => {
                    const rid = String(r._rid)
                    return (
                      <tr key={rid + i}>
                        <td className="rid">{rid}</td>
                        {columns.map((c) => (
                          <td key={c.name}>{r[c.name] === null || r[c.name] === undefined ? <span className="muted">NULL</span> : String(r[c.name])}</td>
                        ))}
                        <td>
                          <div className="cell-actions">
                            <button className="btn small" onClick={() => {
                              const v: Record<string, string> = {}
                              columns.forEach((c) => (v[c.name] = r[c.name] === null || r[c.name] === undefined ? '' : String(r[c.name])))
                              setEdit({ mode: 'edit', rid, values: v })
                            }}>编辑</button>
                            <button className="btn small danger" onClick={() => doDelete(rid)}>删除</button>
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <Pager offset={page.offset} limit={page.limit} total={page.total}
              onPage={(o) => setOffset(o)} />
            <div className="muted" style={{ fontSize: 12 }}>
              本次查询 {page.meta.elapsedMs} ms · 分页由 MiniDB 内核完成 · 编辑/删除按 RID 精确定位
            </div>
          </>
        )}

      {edit && schema && (
        <Modal title={edit.mode === 'insert' ? `在 ${table} 中添加记录` : `编辑 ${table} 记录（RID ${edit.rid}）`}
          onClose={() => setEdit(null)}>
          <div style={{ display: 'grid', gap: 10 }}>
            {columns.map((c) => (
              <label key={c.name} className="row" style={{ flexWrap: 'nowrap' }}>
                <span style={{ width: 150, flexShrink: 0 }}>
                  {c.name}
                  <span className="muted" style={{ fontSize: 12, marginLeft: 4 }}>
                    {c.type}{c.maxLength ? `(${c.maxLength})` : ''}
                  </span>
                </span>
                <input type="text" style={{ flex: 1 }} value={edit.values[c.name] ?? ''}
                  placeholder={c.type === 'BIGINT' ? '整数（可超 JS 精度）' : ''}
                  onChange={(e) => setEdit({ ...edit, values: { ...edit.values, [c.name]: e.target.value } })} />
              </label>
            ))}
          </div>
          <p className="muted" style={{ fontSize: 12.5 }}>
            留空表示 NULL（当前内核存储层不支持 NULL，留空的列将存入类型默认行为）。
            {session?.txn?.active && ' · 当前处于显式事务中，修改将随事务提交/回滚。'}
          </p>
          <div className="modal-actions">
            <button className="btn" onClick={() => setEdit(null)}>取消</button>
            <button className="btn primary" onClick={saveEdit}>
              {edit.mode === 'insert' ? '添加' : '保存'}
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function convertValue(raw: string, col: ColumnInfo): unknown {
  switch (col.type) {
    case 'INT': return Number.parseInt(raw, 10)
    case 'BIGINT': return raw.trim() // BIGINT 用字符串传输（计划书 §35）
    case 'DOUBLE': return Number.parseFloat(raw)
    default: return raw
  }
}

// ---------------- 结构视图 ----------------

function SchemaView({ table }: { table: string }) {
  const [schema, setSchema] = useState<SchemaInfo | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)

  useEffect(() => {
    api.schema(table).then(setSchema).catch((e) => e instanceof ApiFailure && setError(e))
  }, [table])

  if (error) return <ErrorBox error={error} />
  if (!schema) return <div className="empty-state">加载中…</div>

  return (
    <div className="card">
      <h2>{schema.table} 的结构</h2>
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>字段</th><th>类型</th><th>长度</th><th>定长</th></tr></thead>
          <tbody>
            {schema.columns.map((c) => (
              <tr key={c.name}>
                <td><b>{c.name}</b></td>
                <td className="mono">{c.type}</td>
                <td>{c.maxLength ?? '—'}</td>
                <td>{c.fixed ? '是' : '否'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted" style={{ fontSize: 13 }}>
        记录格式：{schema.fixedLength ? `定长记录（${schema.fixedRecordSize} 字节/行）` : '变长记录（含 VARCHAR 列）'}。
        MiniDB 当前支持 INT / BIGINT / DOUBLE / VARCHAR(n≤3000)；唯一性通过真实唯一索引实现（不提供虚假 PRIMARY KEY 语义）。
      </p>
    </div>
  )
}

// ---------------- 索引迷你视图 ----------------

function IndexMiniView({ table }: { table: string }) {
  const { tables } = useApp()
  const info = tables.find((t) => t.name === table)
  return (
    <div className="card">
      <h2>{table} 的索引</h2>
      {info?.indexes.length ? (
        <div className="table-wrap">
          <table className="grid">
            <thead><tr><th>索引</th><th>列</th><th>键类型</th><th>唯一</th></tr></thead>
            <tbody>
              {info.indexes.map((ix) => (
                <tr key={ix.name}>
                  <td className="mono">{ix.name}</td>
                  <td>{ix.column}</td>
                  <td>{ix.keyType}</td>
                  <td>是（MiniDB 索引均为唯一索引）</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <div className="muted">该表暂无索引。可在"索引与计划"页创建。</div>}
    </div>
  )
}
