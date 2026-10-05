import React, { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api, ApiFailure, storedSessionId, storeSessionId } from '../api/client'
import type { RecentDb, SessionState, TableInfo } from '../types'

const RECENT_KEY = 'minidb-studio-recent'

interface AppState {
  ready: boolean
  connected: boolean
  session: SessionState | null
  tables: TableInfo[]
  /** 实验库操作进行中的标志（实验室独立于会话数据库） */
  refresh: () => Promise<void>
  addRecent: (path: string) => void
  removeRecent: (path: string) => void
  recents: RecentDb[]
  /** 全局确认对话框 */
  confirm: (opts: { title: string; message: string; actionText: string; danger?: boolean }) => Promise<boolean>
  /** 全局消息条 */
  notify: (message: string, kind?: 'info' | 'error' | 'success') => void
}

const Ctx = createContext<AppState | null>(null)

export function useApp(): AppState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useApp 必须在 AppProvider 内使用')
  return v
}

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false)
  const [connected, setConnected] = useState(false)
  const [session, setSession] = useState<SessionState | null>(null)
  const [tables, setTables] = useState<TableInfo[]>([])
  const [recents, setRecents] = useState<RecentDb[]>(() => {
    try {
      return JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]') as RecentDb[]
    } catch {
      return []
    }
  })
  const [confirmState, setConfirmState] = useState<{
    resolve: (b: boolean) => void
    title: string
    message: string
    actionText: string
    danger: boolean
  } | null>(null)
  const [toast, setToast] = useState<{ message: string; kind: string } | null>(null)

  const refresh = useCallback(async () => {
    try {
      const s = await api.sessionState()
      setSession(s)
      setConnected(true)
      if (s.hasDb) {
        try {
          setTables(await api.tables())
        } catch {
          setTables([])
        }
      } else {
        setTables([])
      }
    } catch (e) {
      if (e instanceof ApiFailure && e.code === 'SESSION_EXPIRED') {
        // 计划书 §43：原会话已结束 → 新会话
        const fresh = await api.createSession()
        storeSessionId(fresh.sessionId)
        setSession(fresh)
        setConnected(true)
        setTables([])
        notify('原会话已结束，未提交事务已经回滚。')
      } else {
        setConnected(false)
      }
    }
  }, [])

  useEffect(() => {
    ;(async () => {
      try {
        await api.health()
        setConnected(true)
        if (storedSessionId()) {
          try {
            const s = await api.sessionState()
            setSession(s)
            if (s.hasDb) setTables(await api.tables())
          } catch (e) {
            if (e instanceof ApiFailure && e.code === 'SESSION_EXPIRED') {
              const fresh = await api.createSession()
              storeSessionId(fresh.sessionId)
              setSession(fresh)
              notify('原会话已结束，未提交事务已经回滚。')
            }
          }
        } else {
          const fresh = await api.createSession()
          storeSessionId(fresh.sessionId)
          setSession(fresh)
        }
      } catch {
        setConnected(false)
      } finally {
        setReady(true)
      }
    })()
  }, [])

  // 会话活动时低频同步事务状态（空闲回收提示 / 外部变化）
  useEffect(() => {
    const t = setInterval(() => {
      if (storedSessionId()) refresh()
    }, 30000)
    return () => clearInterval(t)
  }, [refresh])

  const addRecent = useCallback((path: string) => {
    setRecents((prev) => {
      const next = [
        { path, lastOpened: Date.now() },
        ...prev.filter((r) => r.path !== path),
      ].slice(0, 8)
      localStorage.setItem(RECENT_KEY, JSON.stringify(next))
      return next
    })
  }, [])

  const removeRecent = useCallback((path: string) => {
    setRecents((prev) => {
      const next = prev.filter((r) => r.path !== path)
      localStorage.setItem(RECENT_KEY, JSON.stringify(next))
      return next
    })
  }, [])

  const confirm = useCallback((opts: { title: string; message: string; actionText: string; danger?: boolean }) => {
    return new Promise<boolean>((resolve) => {
      setConfirmState({ ...opts, danger: opts.danger ?? true, resolve })
    })
  }, [])

  const notify = useCallback((message: string, kind: string = 'info') => {
    setToast({ message, kind })
    setTimeout(() => setToast(null), 3500)
  }, [])

  const value: AppState = {
    ready, connected, session, tables, refresh, addRecent, removeRecent, recents, confirm, notify,
  }

  return (
    <Ctx.Provider value={value}>
      {children}
      {confirmState && (
        <div className="modal-mask" onClick={() => { confirmState.resolve(false); setConfirmState(null) }}>
          <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={confirmState.title}>
            <h3>{confirmState.title}</h3>
            <p className="muted">{confirmState.message}</p>
            <div className="modal-actions">
              <button className="btn" onClick={() => { confirmState.resolve(false); setConfirmState(null) }}>
                取消
              </button>
              <button
                className={confirmState.danger ? 'btn danger' : 'btn primary'}
                onClick={() => { confirmState.resolve(true); setConfirmState(null) }}
              >
                {confirmState.actionText}
              </button>
            </div>
          </div>
        </div>
      )}
      {toast && <div className={`toast toast-${toast.kind}`}>{toast.message}</div>}
    </Ctx.Provider>
  )
}

export { ApiFailure }
