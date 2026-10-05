import React, { useEffect, useMemo, useState } from 'react'
import type { PlanNode } from '../types'
import { nav } from '../router'
import { useApp } from '../stores/app'

/** 全局布局：顶部状态栏 + 左侧导航 + 主工作区（计划书 §4/§5） */
export function StudioLayout({ children, route }: { children: React.ReactNode; route: string }) {
  const { session, connected, tables } = useApp()
  const txn = session?.txn

  return (
    <div className="app">
      <header className="topbar">
        <span
          className="brand"
          style={{ cursor: 'pointer' }}
          onClick={() => nav('/start')}
          title="返回开始页"
        >
          MiniDB Studio
        </span>
        <span className="stat">
          数据库：<b>{session?.dbPath ? session.dbPath.split(/[\\/]/).pop() : '未打开'}</b>
        </span>
        {session?.dbPath && (
          <span className="badge">{session.isWorkDb ? '工作库' : '实验库'}</span>
        )}
        <span className="stat" title={session?.dbPath ?? ''}>
          连接：
          <span className={`dot ${connected ? 'on' : 'off'}`} aria-hidden />
          <b>{connected ? '已连接' : '已断开'}</b>
        </span>
        <span className="stat">
          事务：
          <span className={`dot ${txn?.active ? 'txn' : 'on'}`} aria-hidden />
          <b>{txn?.active ? `ACTIVE · ${txn.isolation}` : '自动提交'}</b>
        </span>
        <span className="stat muted">
          表：{tables.length}
        </span>
        <span className="spacer" />
      </header>
      <div className="body">
        <nav className="sidenav">
          <div className="group">开始</div>
          <a className={route === '/start' ? 'active' : ''} onClick={() => nav('/start')}>
            <span className="nav-label">开始页</span>{'🏠'}
          </a>
          <div className="group">数据库</div>
          <a className={route === '/db' ? 'active' : ''} onClick={() => nav('/db')}>
            <span className="nav-label">数据浏览</span>{'📋'}
          </a>
          <a className={route === '/sql' ? 'active' : ''} onClick={() => nav('/sql')}>
            <span className="nav-label">SQL 工作台</span>{'⌨️'}
          </a>
          <a className={route === '/index' ? 'active' : ''} onClick={() => nav('/index')}>
            <span className="nav-label">索引与计划</span>{'🌳'}
          </a>
          <div className="group">内核实验室</div>
          <a className={route === '/lab/storage' ? 'active' : ''} onClick={() => nav('/lab/storage')}>
            <span className="nav-label">存储与缓冲池</span>{'💾'}
          </a>
          <a className={route === '/lab/pipeline' ? 'active' : ''} onClick={() => nav('/lab/pipeline')}>
            <span className="nav-label">SQL 执行流程</span>{'🔍'}
          </a>
          <a className={route === '/lab/txn' ? 'active' : ''} onClick={() => nav('/lab/txn')}>
            <span className="nav-label">事务与锁</span>{'🔒'}
          </a>
          <a className={route === '/lab/recovery' ? 'active' : ''} onClick={() => nav('/lab/recovery')}>
            <span className="nav-label">WAL 与恢复</span>{'⚡'}
          </a>
          <a className={route === '/lab/perf' ? 'active' : ''} onClick={() => nav('/lab/perf')}>
            <span className="nav-label">性能实验</span>{'⏱️'}
          </a>
          <a className={route === '/lab/jdbc' ? 'active' : ''} onClick={() => nav('/lab/jdbc')}>
            <span className="nav-label">JDBC</span>{'☕'}
          </a>
          <div className="group">学习与帮助</div>
          <a className={route === '/help' ? 'active' : ''} onClick={() => nav('/help')}>
            <span className="nav-label">学习与帮助</span>{'📖'}
          </a>
        </nav>
        <main className={`main ${route === '/db' ? 'no-pad' : ''}`}>{children}</main>
      </div>
    </div>
  )
}

/** 页面状态（计划书 §37）：Loading / Empty / Success / Error / Disconnected */
export function Loading({ text = '加载中…' }: { text?: string }) {
  return <div className="empty-state">{text}</div>
}

export function Empty({ icon = '📭', message, children }: { icon?: string; message: string; children?: React.ReactNode }) {
  return (
    <div className="empty-state">
      <div className="big">{icon}</div>
      <div>{message}</div>
      {children && <div style={{ marginTop: 12 }}>{children}</div>}
    </div>
  )
}

export function ErrorBox({ error }: { error: { code: string; message: string; line?: number; column?: number } | string }) {
  if (typeof error === 'string') return <div className="error-box">{error}</div>
  const loc = error.line !== undefined ? `（第 ${error.line} 行${error.column !== undefined ? ` 第 ${error.column} 列` : ''}）` : ''
  return (
    <div className="error-box">
      <b>[{error.code}]</b> {error.message}
      {loc && <span className="muted"> {loc}</span>}
    </div>
  )
}

/** 二次确认对话框（危险操作专用，计划书 §38） */


/** 通用模态窗口（Esc 关闭，计划书 §40） */
export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-mask" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal" role="dialog" aria-label={title}>
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h3 style={{ margin: 0 }}>{title}</h3>
          <button className="btn small" onClick={onClose} aria-label="关闭">✕</button>
        </div>
        <div style={{ marginTop: 12 }}>{children}</div>
      </div>
    </div>
  )
}

/** 计划树渲染（真实 explain 输出） */
export function PlanTree({ tree, text }: { tree: PlanNode[]; text?: string }) {
  const render = (node: PlanNode, depth: number): React.ReactNode => (
    <div key={`${node.op}-${depth}-${Math.random()}`}>
      <div style={{ paddingLeft: depth * 22, fontFamily: 'var(--mono)', fontSize: 13 }}>
        <span className="flow-node" style={{ display: 'inline-block', padding: '2px 10px', margin: '2px 0' }}>
          {node.op}
        </span>
      </div>
      {node.children.map((c, i) => render(c, depth + 1))}
    </div>
  )
  return (
    <div>
      {tree.map((n, i) => render(n, 0))}
      {text && <pre className="code" style={{ marginTop: 10 }}>{text}</pre>}
    </div>
  )
}

/** 分页组件 */
export function Pager({ offset, limit, total, onPage }: {
  offset: number; limit: number; total: number; onPage: (offset: number) => void
}) {
  const prev = Math.max(0, offset - limit)
  const next = offset + limit
  const page = Math.floor(offset / limit) + 1
  const pages = Math.max(1, Math.ceil(total / limit))
  return (
    <div className="pager">
      <span className="muted">
        第 {page} / {pages} 页 · 共 {total} 行
      </span>
      <button className="btn small" disabled={offset === 0} onClick={() => onPage(prev)}>上一页</button>
      <button className="btn small" disabled={next >= total} onClick={() => onPage(next)}>下一页</button>
    </div>
  )
}

/** CSV 导出（计划书 P1：数据 CSV 导出） */
export function exportCsv(columns: string[], rows: unknown[][], filename: string) {
  const esc = (v: unknown) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const lines = [columns.join(','), ...rows.map((r) => r.map(esc).join(','))]
  const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.click()
  URL.revokeObjectURL(a.href)
}

/** localStorage SQL 历史（计划书 §12） */
export interface HistoryItem {
  sql: string
  time: number
  db: string
  ok: boolean
  elapsedMs: number
}

const HIST_KEY = 'minidb-studio-sql-history'

export function loadHistory(): HistoryItem[] {
  try {
    return JSON.parse(localStorage.getItem(HIST_KEY) ?? '[]')
  } catch {
    return []
  }
}

export function pushHistory(item: HistoryItem) {
  const list = [item, ...loadHistory().filter((h) => h.sql !== item.sql)].slice(0, 100)
  localStorage.setItem(HIST_KEY, JSON.stringify(list))
}

export function useHistory() {
  const [hist, setHist] = useState<HistoryItem[]>(() => loadHistory())
  const add = (item: HistoryItem) => {
    pushHistory(item)
    setHist(loadHistory())
  }
  return useMemo(() => ({ hist, add }), [hist])
}
