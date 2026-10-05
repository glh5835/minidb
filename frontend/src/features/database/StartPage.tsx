import React, { useEffect, useState } from 'react'
import { api, ApiFailure } from '../../api/client'
import { useApp } from '../../stores/app'
import { ErrorBox } from '../../components/ui'
import { nav } from '../../router'

function fmtTime(ts: number): string {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ` +
    `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** 开始页（计划书 §6）：快速开始 + 最近数据库 + 新用户引导 */
export default function StartPage() {
  const { session, refresh, addRecent, removeRecent, recents, notify, confirm } = useApp()
  const [error, setError] = useState<ApiFailure | null>(null)
  const [busy, setBusy] = useState(false)
  const [createPath, setCreatePath] = useState('')
  const [openPath, setOpenPath] = useState('')
  const [missing, setMissing] = useState<Record<string, boolean>>({})

  // 检查最近列表中的文件是否仍存在
  useEffect(() => {
    recents.forEach(async (r) => {
      try {
        const c = await api.checkDatabase(r.path)
        setMissing((m) => ({ ...m, [r.path]: !c.exists }))
      } catch {
        setMissing((m) => ({ ...m, [r.path]: true }))
      }
    })
  }, [recents.length])

  const open = async (path: string) => {
    setBusy(true)
    setError(null)
    try {
      await api.openDatabase(path)
      addRecent(path)
      await refresh()
      notify(`已打开 ${path.split(/[\\/]/).pop()}`, 'success')
      nav('/db')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const createDb = async () => {
    if (!createPath.trim()) return
    setBusy(true)
    setError(null)
    try {
      await api.createDatabase(createPath.trim())
      addRecent(createPath.trim())
      await refresh()
      notify('数据库已创建', 'success')
      nav('/db')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  const sample = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.createSample('')
      addRecent('school.db')
      await refresh()
      notify('示例数据库已创建（school.db）', 'success')
      nav('/db')
    } catch (e) {
      if (e instanceof ApiFailure) setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="start-hero">
        <h1>MiniDB Studio</h1>
        <p className="muted">连接真实 MiniDB 内核的本地工作台：可视化管理 · SQL 开发 · 内核观察 · 教学实验</p>
      </div>

      <div className="quick-actions">
        <div className="quick-action" onClick={() => document.getElementById('create-db-input')?.focus()}>
          <div className="icon">＋</div>
          <div className="title">创建数据库</div>
          <div className="desc">新建一个 .db 数据库文件</div>
        </div>
        <div className="quick-action" onClick={() => document.getElementById('open-db-input')?.focus()}>
          <div className="icon">📂</div>
          <div className="title">打开数据库</div>
          <div className="desc">输入路径打开已有数据库</div>
        </div>
        <div className="quick-action" onClick={sample}>
          <div className="icon">🎓</div>
          <div className="title">创建示例数据库</div>
          <div className="desc">school.db：学生/课程/成绩</div>
        </div>
      </div>

      <div className="card">
        <div className="row" style={{ marginBottom: 8 }}>
          <input
            id="create-db-input"
            type="text"
            placeholder="新数据库路径，如 mydata/school.db"
            style={{ flex: 1 }}
            value={createPath}
            onChange={(e) => setCreatePath(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && createDb()}
          />
          <button className="btn primary" disabled={busy || !createPath.trim()} onClick={createDb}>
            创建
          </button>
        </div>
        <div className="row">
          <input
            id="open-db-input"
            type="text"
            placeholder="或输入已有数据库路径打开，如 data/school.db"
            style={{ flex: 1 }}
            value={openPath}
            onChange={(e) => setOpenPath(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && openPath.trim() && open(openPath.trim())}
          />
          <button className="btn" disabled={busy || !openPath.trim()} onClick={() => open(openPath.trim())}>
            打开
          </button>
        </div>
        {error && <ErrorBox error={error} />}
      </div>

      <div className="card">
        <h2>最近数据库</h2>
        {recents.length === 0 ? (
          <div className="muted">暂无记录。创建或打开一个数据库后会显示在这里。</div>
        ) : (
          <div className="recent-list">
            {recents.map((r) => (
              <div key={r.path} className={`recent-item ${missing[r.path] ? 'missing' : ''}`}
                onClick={() => !missing[r.path] && open(r.path)}>
                <span>{missing[r.path] ? '⚠️' : '🗄️'}</span>
                <div>
                  <div className="name">{r.path.split(/[\\/]/).pop()}</div>
                  <div className="path">{r.path}</div>
                  {missing[r.path] && <div style={{ color: 'var(--danger)', fontSize: 12 }}>数据库文件不存在</div>}
                </div>
                <span className="time">{fmtTime(r.lastOpened)}</span>
                <button
                  className="btn small"
                  onClick={async (e) => {
                    e.stopPropagation()
                    if (await confirm({
                      title: '从最近列表移除',
                      message: `确定移除 ${r.path}？仅移除记录，不删除文件。`,
                      actionText: '移除',
                      danger: false,
                    })) removeRecent(r.path)
                  }}
                >
                  移除
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="card">
        <h2>新用户引导</h2>
        <p className="muted">首次使用？按以下顺序操作，几分钟即可建立完整认知：</p>
        <ol className="lab-steps">
          <li><b>创建示例数据库</b> —— 点击上方 🎓，得到 school.db（含 student / course / score 三张表与索引）</li>
          <li><b>浏览 student 表</b> —— 进入"数据浏览"，直接查看、编辑、增删记录（无需 SQL）</li>
          <li><b>执行 SELECT</b> —— 在"SQL 工作台"输入 SELECT * FROM student WHERE score &gt; 80;</li>
          <li><b>创建一个索引</b> —— 在"索引与计划"为 student(age) 建索引</li>
          <li><b>查看执行计划</b> —— 对比建索引前后 WHERE 条件查询的计划树变化</li>
        </ol>
        {session?.hasDb && (
          <p>
            <button className="btn primary" onClick={() => nav('/db')}>继续：进入数据浏览 →</button>
          </p>
        )}
      </div>
    </div>
  )
}
