// 统一响应协议（计划书 §34）
export interface ApiEnvelope<T = unknown> {
  success: boolean
  data?: T
  meta?: Record<string, unknown>
  error?: ApiError
}

export interface ApiError {
  code: string
  message: string
  line?: number
  column?: number
  details?: string
}

// 会话与数据库
export interface TxnState {
  active: boolean
  autoCommit: boolean
  isolation: 'READ_COMMITTED' | 'REPEATABLE_READ' | string
  txnId?: string
  heldLocks?: string[]
  elapsedMs?: number
}

export interface SessionState {
  sessionId: string
  dbPath: string | null
  hasDb: boolean
  txn: TxnState
  tableNames?: string[]
  isWorkDb?: boolean
}

export interface TableInfo {
  name: string
  rowCount: number
  pageCount: number
  fixedLength: boolean
  indexes: { name: string; column: string; keyType: string }[]
}

export interface ColumnInfo {
  name: string
  type: 'INT' | 'BIGINT' | 'DOUBLE' | 'VARCHAR' | string
  maxLength: number | null
  fixed: boolean
}

export interface SchemaInfo {
  table: string
  columns: ColumnInfo[]
  fixedLength: boolean
  fixedRecordSize: number
}

export interface RowsPage {
  rows: Record<string, unknown>[]
  total: number
  offset: number
  limit: number
  meta: { elapsedMs: number }
}

// SQL
export interface StmtResult {
  index: number
  sql: string
  line: number
  column: number
  success: boolean
  message?: string
  columns?: string[]
  rows?: unknown[][]
  rowCount?: number
  truncated?: boolean
  notice?: string
  error?: ApiError
  elapsedMs: number
  txn: TxnState
}

export interface SqlExecResult {
  statements: StmtResult[]
  txn: TxnState
  elapsedMs: number
}

export interface PlanNode {
  op: string
  children: PlanNode[]
}

export interface PlanResult {
  text: string
  tree: PlanNode[]
}

// 索引与 B+ 树
export interface IndexInfo {
  name: string
  table: string
  column: string
  keyType: string
  rootPage: number
  height: number
  leafNodes: number
  internalNodes: number
  avgLeafUtilization: number
  totalKeys: number
}

export interface BTreeNode {
  pageId: number
  kind: 'LEAF' | 'INTERNAL' | string
  keys: string[]
  children: number[] | null
  next: number
  prev: number
  rids: string[] | null
  usedBytes: number
  totalBytes: number
}

export interface BTreeSnapshot {
  root: number
  height: number
  stringKeys: boolean
  nodes: BTreeNode[]
  elapsedMs: number
}

// 快照
export interface BufferPoolSnapshot {
  capacity: number
  size: number
  hits: number
  misses: number
  evictions: number
  writebacks: number
  diskPages: number
  bitmapPages?: number
  dirtyPages?: number
  pages: { pageId: number; pin: number; dirty: boolean; pageLsn: string | null; type: string }[]
  dbFile?: string
  lastAction?: string
}

export interface LockSnapshot {
  locks: { key: string; holders: string[]; mode: string | null; waiting: string[] }[]
}

export interface WalRecord {
  lsn: string
  type: string
  txnId: string
  table: string
  rid: string | null
  hasBefore: boolean
  hasAfter: boolean
}

// 最近数据库（浏览器 localStorage，计划书 §6.2）
export interface RecentDb {
  path: string
  lastOpened: number
}
