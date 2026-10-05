import type {
  ApiEnvelope, BTreeSnapshot, BufferPoolSnapshot, IndexInfo, LockSnapshot, PlanResult,
  RowsPage, SchemaInfo, SessionState, SqlExecResult, TableInfo, TxnState, WalRecord,
} from '../types'

const BASE = '/api/v1'
const SESSION_KEY = 'minidb-studio-session'
const SESSION_COOKIE = 'minidb-session'

export function storedSessionId(): string | null {
  return localStorage.getItem(SESSION_KEY)
}

export function storeSessionId(id: string) {
  localStorage.setItem(SESSION_KEY, id)
  // 同时写 Cookie：部分内嵌浏览器/代理会剥离自定义请求头，Cookie 由浏览器自动携带
  document.cookie = `${SESSION_COOKIE}=${id}; Path=/; SameSite=Lax`
}

export class ApiFailure extends Error {
  code: string
  line?: number
  column?: number
  details?: string

  constructor(code: string, message: string, line?: number, column?: number, details?: string) {
    super(message)
    this.code = code
    this.line = line
    this.column = column
    this.details = details
  }
}

async function call<T>(method: string, path: string, body?: unknown, session = true): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  let url = BASE + path
  if (session) {
    const sid = storedSessionId()
    if (sid) {
      headers['X-MiniDB-Session'] = sid
      // 双保险：部分内嵌浏览器会剥离自定义请求头与 Cookie，查询参数通道始终可达
      url += (url.includes('?') ? '&' : '?') + 'sessionId=' + encodeURIComponent(sid)
    }
  }
  let resp: Response
  try {
    resp = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiFailure('DISCONNECTED', '无法连接 MiniDB 服务器，请确认服务已启动。')
  }
  let env: ApiEnvelope<T>
  try {
    env = (await resp.json()) as ApiEnvelope<T>
  } catch {
    throw new ApiFailure('PROTOCOL_ERROR', `服务器返回了无法解析的响应（HTTP ${resp.status}）`)
  }
  if (!env.success || env.error) {
    const e = env.error ?? { code: 'UNKNOWN', message: '未知错误' }
    throw new ApiFailure(e.code, e.message, e.line, e.column, e.details)
  }
  return env.data as T
}

// ---- 会话 ----
export const api = {
  health: () => call<{ status: string }>('GET', '/health', undefined, false),
  createSession: () => call<SessionState>('POST', '/sessions', {}),
  sessionState: () => call<SessionState>('GET', '/session'),
  closeSession: () => call<{ closed: boolean }>('DELETE', '/session'),

  // 数据库
  createDatabase: (path: string) => call<SessionState>('POST', '/databases/create', { path }),
  openDatabase: (path: string) => call<SessionState>('POST', '/databases/open', { path }),
  closeDatabase: () => call<SessionState>('POST', '/databases/close', {}),
  checkDatabase: (path: string) =>
    call<{ path: string; exists: boolean; open: boolean }>('POST', '/databases/check', { path }),
  createSample: (dir: string) => call<SessionState>('POST', '/databases/sample', { dir }),

  // 表
  tables: () => call<TableInfo[]>('GET', '/tables'),
  schema: (table: string) => call<SchemaInfo>('GET', `/tables/${encodeURIComponent(table)}/schema`),
  createTable: (name: string, columns: { name: string; type: string; size?: number }[]) =>
    call<unknown>('POST', '/tables', { name, columns }),
  dropTable: (name: string) => call<unknown>('DELETE', `/tables/${encodeURIComponent(name)}`),

  // 数据（RID 精确操作）
  rows: (table: string, q: { offset?: number; limit?: number; sort?: string; dir?: string;
    filterColumn?: string; filterOp?: string; filterValue?: string }) => {
    const p = new URLSearchParams()
    Object.entries(q).forEach(([k, v]) => {
      if (v !== undefined && v !== '') p.set(k, String(v))
    })
    return call<RowsPage>('GET', `/tables/${encodeURIComponent(table)}/rows?${p}`)
  },
  insertRow: (table: string, values: Record<string, unknown>) =>
    call<{ rid: string }>('POST', `/tables/${encodeURIComponent(table)}/rows`, values),
  updateRow: (table: string, rid: string, values: Record<string, unknown>) => {
    const [page, slot] = rid.split('/')
    return call<{ oldRid: string; newRid: string; migrated: boolean }>(
      'PUT', `/tables/${encodeURIComponent(table)}/rows/${page}/${slot}`, values)
  },
  deleteRow: (table: string, rid: string) => {
    const [page, slot] = rid.split('/')
    return call<unknown>('DELETE', `/tables/${encodeURIComponent(table)}/rows/${page}/${slot}`)
  },

  // SQL
  executeSql: (sql: string, onlyIndex?: number) =>
    call<SqlExecResult>('POST', '/sql/execute', { sql, onlyIndex }),
  plan: (sql: string) => call<PlanResult>('POST', '/sql/plan', { sql }),
  pipeline: (sql: string) => call<Record<string, unknown>>('POST', '/sql/pipeline', { sql }),

  // 事务
  txnState: () => call<TxnState>('GET', '/transactions'),
  begin: (isolation?: string) => call<TxnState>('POST', '/transactions/begin', { isolation }),
  commit: () => call<TxnState>('POST', '/transactions/commit', {}),
  rollback: () => call<TxnState>('POST', '/transactions/rollback', {}),

  // 索引
  indexes: () => call<IndexInfo[]>('GET', '/indexes'),
  createIndex: (name: string, table: string, column: string) =>
    call<unknown>('POST', '/indexes', { name, table, column }),
  dropIndex: (name: string) => call<unknown>('DELETE', `/indexes/${encodeURIComponent(name)}`),
  btree: (name: string) => call<BTreeSnapshot>('GET', `/indexes/${encodeURIComponent(name)}/btree`),

  // 快照
  bufferPool: () => call<BufferPoolSnapshot>('GET', '/snapshots/buffer-pool'),
  locks: () => call<LockSnapshot>('GET', '/snapshots/locks'),
  wal: () => call<{ records: WalRecord[]; fileBytes: number }>('GET', '/snapshots/wal'),
  flush: () => call<{ flushed: boolean }>('POST', '/snapshots/flush', {}),

  // 实验室
  lab: {
    txnSetup: () => call<Record<string, unknown>>('POST', '/labs/txn/setup', {}),
    txnExec: (body: { session: string; action: string; sql?: string; isolation?: string }) =>
      call<Record<string, unknown>>('POST', '/labs/txn/exec', body),
    txnPoll: () => call<Record<string, { state: string; result?: Record<string, unknown> }>>('GET', '/labs/txn/poll'),
    txnState: () => call<Record<string, unknown>>('GET', '/labs/txn/state'),
    recoveryRun: () => call<Record<string, unknown>>('POST', '/labs/recovery/run', {}),
    storageSetup: () => call<Record<string, unknown>>('POST', '/labs/storage/setup', {}),
    storageState: (table: string) => call<Record<string, unknown>>('GET', `/labs/storage/state?table=${encodeURIComponent(table)}`),
    storageAct: (body: Record<string, unknown>) => call<Record<string, unknown>>('POST', '/labs/storage/act', body),
    bpSetup: () => call<BufferPoolSnapshot>('POST', '/labs/bp/setup', {}),
    bpState: () => call<BufferPoolSnapshot>('GET', '/labs/bp/state'),
    bpAct: (op: string, extra?: Record<string, unknown>) =>
      call<BufferPoolSnapshot>('POST', '/labs/bp/act', { op, ...extra }),
    perfRun: (rows: number, queries: number, warmup: number) =>
      call<Record<string, unknown>>('POST', '/labs/perf/run', { rows, queries, warmup }),
    jdbcRun: () => call<Record<string, unknown>>('POST', '/labs/jdbc/run', {}),
  },
}
