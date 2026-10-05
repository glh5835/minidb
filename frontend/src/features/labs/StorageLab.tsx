import React, { useCallback, useEffect, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { ErrorBox } from '../../components/ui'
import type { BufferPoolSnapshot } from '../../types'

type AnyMap = Record<string, unknown>

/** 存储与缓冲池实验（计划书 §21/§22）：4KB 页 / Slot / RID 观察 + 缓冲池 pin/dirty/写回 */
export default function StorageLab() {
  return (
    <div>
      <h1 className="page-title">存储与缓冲池实验</h1>
      <p className="page-sub">
        独立实验库（实验库文件独立于工作库）· 所有页数据来自真实 BufferPool 快照 · 不伪造任何结果
      </p>
      <StorageSection />
      <BufferPoolSection />
    </div>
  )
}

function StorageSection() {
  const [state, setState] = useState<AnyMap | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [table, setTable] = useState('fixed_demo')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async (t: string) => {
    try {
      setState(await api.lab.storageState(t))
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    }
  }, [])

  useEffect(() => { load(table) }, [load, table])

  const act = async (body: AnyMap) => {
    setBusy(true)
    setError(null)
    try {
      const r = await api.lab.storageAct({ table, ...body })
      setState(r.state as AnyMap)
      setNote(String(r.result ?? ''))
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const setup = async () => {
    setBusy(true)
    setError(null)
    try {
      setState(await api.lab.storageSetup())
      setNote('实验环境已初始化：fixed_demo（定长表）+ var_demo（变长表）')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const pages = (state?.pages as AnyMap[]) ?? []
  const rows = (state?.rows as AnyMap[]) ?? []

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2>实验一 · 页与记录：4KB 页 / Slot / RID</h2>
        <button className="btn small primary" disabled={busy} onClick={setup}>初始化 / 重置实验库</button>
      </div>
      <p className="muted" style={{ fontSize: 13 }}>
        Database └─ Pages └─ 每页 4KB（4096 字节）。定长表用位图管理槽位，变长表用槽位数组 + 从两端增长的记录区。
        插入记录后立刻观察页使用空间变化。RID = (页号, 槽号)。
      </p>
      {error && <ErrorBox error={error} />}
      {!state ? <div className="muted">点击"初始化实验库"开始。</div> : (
        <>
          <div className="kv" style={{ marginBottom: 12 }}>
            <div className="item"><div className="k">表</div>
              <select value={table} onChange={(e) => setTable(e.target.value)} style={{ width: '100%' }}>
                <option value="fixed_demo">fixed_demo（定长）</option>
                <option value="var_demo">var_demo（变长）</option>
              </select>
            </div>
            <div className="item"><div className="k">行数</div><div className="v">{String(state.rowCount)}</div></div>
            <div className="item"><div className="k">页数</div><div className="v">{String(state.pageCount)}</div></div>
            <div className="item"><div className="k">总空闲字节</div><div className="v">{String(state.totalFreeBytes)}</div></div>
            <div className="item"><div className="k">记录格式</div>
              <div className="v" style={{ fontSize: 13 }}>{String(state.fixedLength) === 'true' ? '定长' : '变长'}</div>
            </div>
          </div>
          <div className="row" style={{ marginBottom: 10 }}>
            <button className="btn small" disabled={busy} onClick={() => act({ op: 'insertFixed', id: Date.now() % 1000, num: 42, score: 1 })}>
              ＋ 插入定长记录
            </button>
            <button className="btn small" disabled={busy} onClick={() => act({ op: 'insertVar', id: Date.now() % 1000, note: `记录-${Date.now() % 10000}` })}>
              ＋ 插入变长记录
            </button>
          </div>
          {note && <div className="notice-box">{note}</div>}
          <h3>页视图（真实页头）</h3>
          <div className="table-wrap">
            <table className="grid">
              <thead><tr><th>页号</th><th>类型</th><th>槽位数</th><th>已用字节</th><th>空闲字节</th><th>下一页</th><th>PageLSN</th></tr></thead>
              <tbody>
                {pages.map((p) => (
                  <tr key={String(p.pageId)}>
                    <td className="mono">{String(p.pageId)}</td>
                    <td>{String(p.type)}</td>
                    <td>{String(p.numSlots)}</td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span className="mono" style={{ width: 46 }}>{String(p.usedBytes)}</span>
                        <div style={{ width: 100, height: 6, background: 'var(--border)', borderRadius: 3 }}>
                          <div style={{ width: `${(Number(p.usedBytes) / 4096) * 100}%`, height: '100%', background: 'var(--primary)', borderRadius: 3 }} />
                        </div>
                      </div>
                    </td>
                    <td className="mono">{String(p.totalFree)}</td>
                    <td className="mono">{String(p.next || '∅')}</td>
                    <td className="mono">{String(p.pageLsn ?? '—')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h3 style={{ marginTop: 12 }}>行与 RID</h3>
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
        </>
      )}
    </div>
  )
}

function BufferPoolSection() {
  const [snap, setSnap] = useState<BufferPoolSnapshot | null>(null)
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)

  const setup = async () => {
    setBusy(true)
    setError(null)
    try {
      setSnap(await api.lab.bpSetup())
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const act = async (op: string, extra?: Record<string, unknown>) => {
    setBusy(true)
    setError(null)
    try {
      setSnap(await api.lab.bpAct(op, extra))
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    api.lab.bpState().then(setSnap).catch(() => {/* 未初始化 */})
  }, [])

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2>实验二 · Buffer Pool：读命中 / 页变脏 / 写回磁盘</h2>
        <button className="btn small primary" disabled={busy} onClick={setup}>初始化 / 重置实验库</button>
      </div>
      <p className="muted" style={{ fontSize: 13 }}>
        读磁盘 → 缓存命中 → 页面变脏 → 写回磁盘。动作：全表扫描观察 miss/淘汰；点查只加载索引路径与数据页；
        更新让页变脏（pin=0, dirty=yes）；flush 按 WAL 先写原则落盘（writebacks 增加）。
      </p>
      {error && <ErrorBox error={error} />}
      {!snap ? <div className="muted">点击"初始化实验库"开始。</div> : (
        <>
          <div className="kv" style={{ marginBottom: 12 }}>
            <div className="item"><div className="k">容量</div><div className="v">{snap.capacity}</div></div>
            <div className="item"><div className="k">当前缓存</div><div className="v">{snap.size}</div></div>
            <div className="item"><div className="k">命中 hits</div><div className="v">{snap.hits}</div></div>
            <div className="item"><div className="k">缺失 misses</div><div className="v">{snap.misses}</div></div>
            <div className="item"><div className="k">淘汰 evictions</div><div className="v">{snap.evictions}</div></div>
            <div className="item"><div className="k">写回 writebacks</div><div className="v">{snap.writebacks}</div></div>
            <div className="item"><div className="k">脏页</div><div className="v">{snap.dirtyPages ?? '—'}</div></div>
            <div className="item"><div className="k">磁盘页</div><div className="v">{snap.diskPages}</div></div>
          </div>
          <div className="row" style={{ marginBottom: 10 }}>
            <button className="btn small" disabled={busy} onClick={() => act('readAll')}>全表扫描</button>
            <button className="btn small" disabled={busy} onClick={() => act('readOne', { id: 7 })}>索引点查 id=7</button>
            <button className="btn small" disabled={busy} onClick={() => act('update', { id: 3 })}>更新 id=3（页变脏）</button>
            <button className="btn small" disabled={busy} onClick={() => act('flush')}>Flush 刷盘</button>
          </div>
          {snap.lastAction && <div className="notice-box">{snap.lastAction}</div>}
          <div className="table-wrap">
            <table className="grid">
              <thead><tr><th>Page</th><th>Pin</th><th>Dirty</th><th>State</th><th>PageLSN</th><th>使用率</th></tr></thead>
              <tbody>
                {snap.pages.map((p) => (
                  <tr key={p.pageId}>
                    <td className="mono">{p.pageId}</td>
                    <td>{p.pin}</td>
                    <td>{p.dirty ? <b style={{ color: 'var(--danger)' }}>Yes</b> : 'No'}</td>
                    <td>{p.pin > 0 ? 'Pinned' : 'Cached'}</td>
                    <td className="mono">{p.pageLsn ?? '—'}</td>
                    <td className="mono">{p.type}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
