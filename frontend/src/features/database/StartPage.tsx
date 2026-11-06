import { useEffect, useRef, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { StudioIcon } from '../../components/StudioIcon'
import { ErrorBox } from '../../components/ui'
import { nav } from '../../router'
import { useApp } from '../../stores/app'
import type { SessionState } from '../../types'
import './StartPage.css'

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function databaseName(path: string): string {
  return path.split(/[\\/]/).pop() || path
}

/** 快速开始、最近数据库和教学入口。 */
export default function StartPage() {
  const { ready, connected, session, tables, refresh, addRecent, removeRecent, recents, notify, confirm } = useApp()
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const [createPath, setCreatePath] = useState('')
  const [openPath, setOpenPath] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [missing, setMissing] = useState<Record<string, boolean>>({})
  const openInput = useRef<HTMLInputElement>(null)
  const createInput = useRef<HTMLInputElement>(null)
  const createToggle = useRef<HTMLButtonElement>(null)
  const currentPath = connected && session?.hasDb ? session.dbPath : null
  const tableCount = session?.tableNames?.length ?? tables.length

  useEffect(() => {
    if (!ready || !connected) return
    let active = true
    Promise.all(recents.map(async ({ path }) => {
      try {
        return [path, !(await api.checkDatabase(path)).exists] as const
      } catch {
        // 服务错误不表示文件不存在，用户仍可尝试打开。
        return [path, false] as const
      }
    })).then((entries) => {
      if (active) setMissing(Object.fromEntries(entries))
    })
    return () => { active = false }
  }, [ready, connected, recents])

  useEffect(() => {
    if (showCreate) createInput.current?.focus()
  }, [showCreate])

  const runAction = async (request: () => Promise<SessionState>, path: string, message: string) => {
    if (busy || !ready) return
    setBusy(true)
    setError(null)
    try {
      await request()
      addRecent(path)
      await refresh()
      notify(message, 'success')
      nav('/db')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const open = (path: string) => runAction(
    () => api.openDatabase(path), path, '已打开 ' + databaseName(path),
  )

  const closeCreate = () => {
    setShowCreate(false)
    createToggle.current?.focus()
  }

  const currentTitle = currentPath ? databaseName(currentPath)
    : !ready ? '正在连接…'
    : connected ? '未打开数据库' : '服务未连接'

  return (
    <div className="start-page">
      <header className="start-title">
        <h1>开始页</h1>
        <p>管理本地数据库、运行 SQL，或观察 MiniDB 内核的工作过程。</p>
      </header>

      <section className="start-hero" aria-labelledby="start-current-title">
        <div className="start-hero-body">
          <div className="start-hero-label"><StudioIcon name="database" />当前数据库</div>
          <h2 id="start-current-title" title={currentPath ?? undefined}>{currentTitle}</h2>
          {currentPath ? (
            <>
              <div className="start-hero-meta">
                <span className={'badge' + (session?.isWorkDb === false ? ' lab' : '')}>{session?.isWorkDb === false ? '实验库' : '工作库'}</span>
                <span className="start-meta-dot" aria-hidden="true" />
                <span>{tableCount} 张表</span>
                <span className="start-meta-dot" aria-hidden="true" />
                <span className="mono start-truncated" title={currentPath}>{currentPath}</span>
              </div>
              <a className="btn primary start-hero-action" href="#/db">进入数据浏览<StudioIcon name="arrow-right" /></a>
            </>
          ) : (
            <>
              <p className="start-hero-meta">
                {!ready ? '正在建立工作台会话。' : connected ? '打开现有数据库，或创建一个新库开始。' : '请确认 MiniDB 服务已启动。'}
              </p>
              <button className="btn primary start-hero-action" type="button" onClick={() => openInput.current?.focus()}>
                填写数据库路径<StudioIcon name="arrow-right" />
              </button>
            </>
          )}
        </div>
        <DatabaseArt />
      </section>

      {error && <div className="start-error" role="alert"><ErrorBox error={error} /></div>}

      <div className="start-panels">
        <section className="card start-panel" aria-labelledby="start-open-title">
          <div className="start-panel-heading">
            <div><h2 id="start-open-title">打开数据库</h2><p>输入本地 .db 文件路径，打开已有数据库。</p></div>
            <span className="start-icon-tile"><StudioIcon name="folder" /></span>
          </div>
          <div className="start-panel-body">
            <form onSubmit={(event) => {
              event.preventDefault()
              const path = openPath.trim()
              if (path) void open(path)
            }}>
              <label className="start-path-label" htmlFor="open-db-input">数据库路径</label>
              <div className="start-path-row">
                <div className="start-path-field">
                  <StudioIcon name="file" />
                  <input id="open-db-input" ref={openInput} type="text" className="mono" placeholder="例如 data/school.db"
                    value={openPath} onChange={(event) => setOpenPath(event.target.value)} />
                </div>
                <button className="btn" type="submit" disabled={busy || !ready || !openPath.trim()}>
                  打开数据库<StudioIcon name="arrow-right" />
                </button>
              </div>
            </form>
            <div className="start-shortcuts">
              <button className="btn" type="button" ref={createToggle} aria-expanded={showCreate} aria-controls="start-create-form"
                disabled={busy || !ready} onClick={() => setShowCreate(!showCreate)}>
                <StudioIcon name="plus" />创建数据库
              </button>
              <button className="btn" type="button" disabled={busy || !ready} onClick={() => {
                void runAction(() => api.createSample(''), 'school.db', '示例数据库已创建（school.db）')
              }}>
                <StudioIcon name="school" />创建示例库
              </button>
            </div>
            <form id="start-create-form" className="start-create-form" hidden={!showCreate} onSubmit={(event) => {
              event.preventDefault()
              const path = createPath.trim()
              if (path) void runAction(() => api.createDatabase(path), path, '数据库已创建')
            }}>
              <label className="start-path-label" htmlFor="create-db-input">新数据库路径</label>
              <div className="start-path-row">
                <input id="create-db-input" ref={createInput} type="text" className="mono" placeholder="例如 data/new.db"
                  value={createPath} onChange={(event) => setCreatePath(event.target.value)} />
                <button className="btn primary" type="submit" disabled={busy || !ready || !createPath.trim()}>创建</button>
                <button className="btn" type="button" disabled={busy} onClick={closeCreate}>取消</button>
              </div>
            </form>
          </div>
        </section>

        <section className="card start-panel" aria-labelledby="start-recent-title">
          <div className="start-panel-heading">
            <div><h2 id="start-recent-title">最近数据库</h2><p>快速打开最近使用的数据库。</p></div>
            <span className="start-icon-tile blue"><StudioIcon name="clock" /></span>
          </div>
          {recents.length === 0 ? (
            <p className="start-recent-empty">创建或打开的数据库会显示在这里。</p>
          ) : (
            <ul className="start-recent-list">
              {recents.map((recent) => {
                const name = databaseName(recent.path)
                const isMissing = missing[recent.path]
                return (
                  <li key={recent.path} className="start-recent-row">
                    <button className="start-recent-open" type="button" disabled={busy || !ready || isMissing}
                      onClick={() => void open(recent.path)}>
                      <span className="start-icon-tile"><StudioIcon name={isMissing ? 'info' : 'database'} /></span>
                      <span className="start-recent-ident">
                        <strong className="start-truncated" title={name}>{name}</strong>
                        <small className="mono start-truncated" title={recent.path}>{recent.path}</small>
                        {isMissing && <span className="start-missing">数据库文件不存在</span>}
                      </span>
                      <time className="start-recent-time">{fmtTime(recent.lastOpened)}</time>
                      <StudioIcon className="start-chevron" name="chevron" />
                    </button>
                    <button className="start-recent-remove" type="button" disabled={busy} title="从最近列表移除" aria-label={'移除 ' + name}
                      onClick={async () => {
                        if (await confirm({
                          title: '从最近列表移除',
                          message: '移除这条最近记录？数据库文件仍会保留。\n' + recent.path,
                          actionText: '移除',
                          danger: false,
                        })) removeRecent(recent.path)
                      }}>
                      <StudioIcon name="x" />
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      </div>

      <section className="start-learn" aria-labelledby="start-learn-title">
        <h2 id="start-learn-title">快速上手</h2>
        <div className="start-learn-links">
          <a className="start-learn-item" href="#/db">
            <span className="start-icon-tile"><StudioIcon name="grid" /></span>
            <span><b>浏览数据表</b><small>查看数据、结构与索引</small></span>
            <StudioIcon className="start-chevron" name="chevron" />
          </a>
          <a className="start-learn-item" href="#/sql">
            <span className="start-icon-tile blue"><StudioIcon name="terminal" /></span>
            <span><b>运行第一条查询</b><small>在 SQL 工作台查看查询结果</small></span>
            <StudioIcon className="start-chevron" name="chevron" />
          </a>
          <a className="start-learn-item" href="#/lab/storage">
            <span className="start-icon-tile violet"><StudioIcon name="layers" /></span>
            <span><b>观察缓冲池</b><small>查看缓存、脏页和刷盘状态</small></span>
            <StudioIcon className="start-chevron" name="chevron" />
          </a>
        </div>
      </section>
    </div>
  )
}

function DatabaseArt() {
  return (
    <svg className="start-hero-art" viewBox="0 0 552 229" fill="none" aria-hidden="true" preserveAspectRatio="xMidYMid slice">
      <defs>
        <pattern id="start-art-dots" width="15" height="15" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="1" fill="#82b3a9" opacity=".43" /></pattern>
        <radialGradient id="start-art-dot-fade"><stop offset="0" stopColor="white" /><stop offset="1" stopColor="black" /></radialGradient>
        <mask id="start-art-dot-mask"><rect width="552" height="229" fill="url(#start-art-dot-fade)" /></mask>
        <linearGradient id="start-art-metal" x1="197" y1="0" x2="373" y2="0" gradientUnits="userSpaceOnUse"><stop stopColor="#519d8a" /><stop offset=".56" stopColor="#8fc5bb" /><stop offset="1" stopColor="#5ca79c" /></linearGradient>
        <linearGradient id="start-art-disc" x1="203" y1="45" x2="375" y2="102" gradientUnits="userSpaceOnUse"><stop stopColor="#effcf7" /><stop offset="1" stopColor="#acd8d1" /></linearGradient>
        <linearGradient id="start-art-platform" x1="160" y1="120" x2="411" y2="214" gradientUnits="userSpaceOnUse"><stop stopColor="#ecfaf6" stopOpacity=".93" /><stop offset="1" stopColor="#b3d9d7" stopOpacity=".6" /></linearGradient>
      </defs>
      <rect width="552" height="229" fill="url(#start-art-dots)" mask="url(#start-art-dot-mask)" />
      <ellipse cx="301" cy="129" rx="220" ry="87" transform="rotate(-14 301 129)" stroke="#a2cbc4" strokeOpacity=".6" />
      <ellipse cx="301" cy="129" rx="186" ry="69" transform="rotate(-14 301 129)" stroke="#b7d8d5" strokeDasharray="4 5" />
      <path d="M114 169h60l34-20m158-49h44l38-34h51M369 160h36l30 18h52M114 83h62l28 20" stroke="#92beb4" strokeWidth="1.4" />
      <g fill="#effcf7" stroke="#76ad9f" strokeWidth="1.3"><circle cx="114" cy="169" r="4" /><circle cx="499" cy="66" r="4" /><circle cx="487" cy="178" r="4" /><circle cx="114" cy="83" r="4" /></g>
      <path d="M147 159 273 123a29 29 0 0 1 16 0l129 35a9 9 0 0 1 0 17l-130 39a29 29 0 0 1-17 0l-125-39a8.5 8.5 0 0 1 1-16Z" fill="#94c4b9" fillOpacity=".39" transform="translate(0 5)" />
      <path d="M147 159 273 123a29 29 0 0 1 16 0l129 35a9 9 0 0 1 0 17l-130 39a29 29 0 0 1-17 0l-125-39a8.5 8.5 0 0 1 1-16Z" fill="url(#start-art-platform)" stroke="#95c0b6" />
      <ellipse cx="284" cy="164" rx="102" ry="28" fill="#80b3a6" opacity=".22" />
      <g stroke="#4d9888" strokeWidth="1.25">
        <path d="M197 127h176v23c0 18-39 33-88 33s-88-15-88-33Z" fill="url(#start-art-metal)" />
        <ellipse cx="285" cy="127" rx="88" ry="28" fill="url(#start-art-disc)" />
        <path d="M197 98h176v23c0 18-39 33-88 33s-88-15-88-33Z" fill="url(#start-art-metal)" />
        <ellipse cx="285" cy="98" rx="88" ry="28" fill="url(#start-art-disc)" />
        <path d="M197 68h176v23c0 18-39 33-88 33s-88-15-88-33Z" fill="url(#start-art-metal)" />
        <ellipse cx="285" cy="68" rx="88" ry="28" fill="url(#start-art-disc)" />
        <ellipse cx="285" cy="68" rx="70" ry="20" stroke="#90c2b7" strokeDasharray="3 3" />
      </g>
      <path d="m254 68 28-9a10 10 0 0 1 6 0l28 9-28 9a10 10 0 0 1-6 0Z" fill="#7ab8a9" fillOpacity=".35" stroke="#6bad9c" />
      <path d="M337 109a60 60 0 0 0 19-8m-19 38a60 60 0 0 0 19-8m-19 37a60 60 0 0 0 19-8" stroke="#d9f5ed" strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="330" cy="111" r="2.6" fill="#edfff6" />
      <circle cx="330" cy="141" r="2.6" fill="#edfff6" />
      <circle cx="330" cy="170" r="2.6" fill="#edfff6" />
      <circle cx="438" cy="44" r="9" fill="#d6eee7" stroke="#b3d7cf" /><path d="M435 44h6m-3-3v6" stroke="#6caa9a" strokeLinecap="round" />
      <circle cx="155" cy="113" r="5" fill="#b5dcd0" fillOpacity=".75" />
    </svg>
  )
}
