import React, { useEffect, useMemo, useState } from 'react'
import type { PlanNode } from '../types'
import { StudioIcon } from './StudioIcon'
import { useApp } from '../stores/app'


const navigation = [
  { label: '工作区', items: [
    { path: '/start', label: '开始页', icon: 'home' },
    { path: '/db', label: '数据浏览', icon: 'grid' },
    { path: '/sql', label: 'SQL 工作台', icon: 'terminal' },
    { path: '/index', label: '索引与计划', icon: 'tree' },
  ] },
  { label: '内核实验室', items: [
    { path: '/lab/storage', label: '存储与缓冲池', icon: 'layers' },
    { path: '/lab/pipeline', label: 'SQL 执行流程', icon: 'activity' },
    { path: '/lab/txn', label: '事务与锁', icon: 'lock' },
    { path: '/lab/recovery', label: 'WAL 与恢复', icon: 'rotate' },
    { path: '/lab/perf', label: '性能实验', icon: 'gauge' },
    { path: '/lab/jdbc', label: 'JDBC', icon: 'plug' },
  ] },
  { label: '支持', items: [
    { path: '/help', label: '学习与帮助', icon: 'book' },
  ] },
] as const

const featuredRoutes = [
  '/start', '/db', '/sql', '/index', '/lab/storage', '/lab/pipeline', '/lab/txn', '/lab/recovery', '/lab/perf', '/lab/jdbc', '/help',
]

/** 全局布局：顶部状态栏 + 左侧导航 + 主工作区（计划书 §4/§5） */
export function StudioLayout({ children, route }: { children: React.ReactNode; route: string }) {
  const { ready, session, connected, tables } = useApp()
  const [collapsed, setCollapsed] = useState(() => window.matchMedia('(max-width: 760px)').matches)
  const txn = session?.txn
  const dbPath = session?.dbPath
  const dbName = dbPath?.split(/[\\/]/).pop()
  const group = navigation.find(({ items }) => items.some(({ path }) => path === route)) ?? navigation[0]
  const current = group.items.find(({ path }) => path === route) ?? navigation[0].items[0]
  const featured = featuredRoutes.includes(current.path)
  const connectionLabel = ready ? connected ? '服务已连接' : '服务已断开' : '正在连接服务'
  const toggleLabel = collapsed ? '展开侧栏' : '收起侧栏'

  return (
    <div className={collapsed ? 'app sidebar-collapsed' : 'app'}>
      <aside className="studio-sidebar">
        <a className="studio-brand" href="#/start" aria-label="MiniDB Studio，返回开始页" title="返回开始页">
          <span className="studio-brand-mark"><StudioIcon name="database" /></span>
          <span className="studio-brand-copy">
            <strong>MiniDB Studio</strong>
            <small>本地数据库工作台</small>
          </span>
        </a>
        <nav className="sidenav" id="studio-navigation" aria-label="工作台导航">
          {navigation.map(({ label, items }) => (
            <div className="nav-group" key={label}>
              <div className="nav-heading">{label}</div>
              {items.map((item) => (
                <a
                  key={item.path}
                  href={'#' + item.path}
                  aria-current={current.path === item.path ? 'page' : undefined}
                  aria-label={collapsed ? item.label : undefined}
                  title={collapsed ? item.label : undefined}
                >
                  <span className="nav-icon"><StudioIcon name={item.icon} /></span>
                  <span className="nav-label">{item.label}</span>
                </a>
              ))}
            </div>
          ))}
        </nav>
        <div className="studio-side-foot" title="MiniDB 内核 · 本地运行">
          <StudioIcon name="chip" />
          <span><strong>MiniDB 内核</strong><small>本地运行</small></span>
        </div>
      </aside>
      <header className="topbar">
        <div className="studio-breadcrumb">
          <button
            type="button" className="sidebar-toggle" aria-label={toggleLabel} title={toggleLabel}
            aria-expanded={!collapsed} aria-controls="studio-navigation"
            onClick={() => setCollapsed((value) => !value)}
          >
            <StudioIcon name="sidebar" />
          </button>
          <span>{group.label}</span>
          <StudioIcon name="chevron" />
          <strong>{current.label}</strong>
        </div>
        <div className="studio-status">
          <span className="studio-status-item">
            <span className={ready ? 'dot ' + (connected ? 'on' : 'off') : 'dot'} aria-hidden="true" />
            {connectionLabel}
          </span>
          <span className="studio-status-item" title={dbPath ?? undefined}>
            <StudioIcon name="database" />
            <span className={dbName ? 'studio-db-name mono' : undefined}>{dbName || '数据库未打开'}</span>
            {dbPath && <span className={session?.isWorkDb ? 'badge' : 'badge lab'}>{session?.isWorkDb ? '工作库' : '实验库'}</span>}
            {dbPath && <span className="studio-table-count">{tables.length} 张表</span>}
          </span>
          {session && (
            <span className={txn?.active ? 'studio-status-item studio-txn-active' : 'studio-status-item'}>
              <StudioIcon name="lock" />
              {txn?.active ? '事务进行中 · ' + txn.isolation : '自动提交'}
            </span>
          )}
        </div>
      </header>
      <main key={route} className={featured ? 'main studio-featured' : 'main'}>{children}</main>
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
