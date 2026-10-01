package minidb.storage;

import minidb.btree.BPlusTree;
import minidb.common.MiniDbException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据库门面：打开文件、管理目录、表与索引。所有页访问都经过 BufferPool。 */
public final class Database implements AutoCloseable {
    private final StorageEngine engine;
    private final Catalog catalog;
    private final minidb.wal.WalLog wal;
    private final minidb.txn.LockManager lockManager = new minidb.txn.LockManager();
    private final java.util.concurrent.atomic.AtomicLong txnCounter =
            new java.util.concurrent.atomic.AtomicLong(1);
    private final Map<String, TableEntry> tables = new LinkedHashMap<>();
    /** 索引名 -> (所属表, 元数据, 打开的 B+ 树) */
    private final Map<String, IndexEntry> indexes = new LinkedHashMap<>();

    private record TableEntry(Catalog.Entry meta, Table table) {}

    public record IndexEntry(String tableName, IndexMeta meta, BPlusTree tree) {}

    private Database(Path file, int bufferPages) {
        this.engine = new StorageEngine(file, bufferPages);
        this.catalog = new Catalog(engine);
        for (Catalog.Entry e : catalog.load()) {
            tables.put(e.name(), new TableEntry(e, openTable(engine, e)));
            for (IndexMeta im : e.indexes()) {
                indexes.put(im.name(), new IndexEntry(e.name(), im, new BPlusTree(engine, im.rootPage())));
            }
        }
        this.wal = new minidb.wal.WalLog(walPath(file));
        // FlushHook：任何脏页落盘前，把该页 pageLsn 之前的 WAL 强制落盘（WAL 先写原则）
        engine.pool().setFlushHook(page -> wal.syncUpTo(page.pageLsn()));
        // 崩溃恢复：redo + undo（协议见 Recovery 注释）
        java.util.List<minidb.wal.WalLog.Rec> records = wal.readAll();
        if (!records.isEmpty()) {
            minidb.wal.Recovery.recover(this, records);
            wal.truncate();
            engine.flush();
        }
    }

    private static java.nio.file.Path walPath(java.nio.file.Path dbFile) {
        return dbFile.resolveSibling(dbFile.getFileName() + ".wal");
    }

    public minidb.wal.WalLog wal() {
        return wal;
    }

    public minidb.txn.LockManager lockManager() {
        return lockManager;
    }

    public long nextTxnId() {
        return txnCounter.getAndIncrement();
    }

    /** 模拟断电：缓冲池不刷盘、日志不 fsync 不截断，直接关闭句柄（OS 缓冲中的日志字节视为丢失）。 */
    public synchronized void crash() {
        wal.abruptClose();
        engine.abruptClose();
    }

    public static Database open(Path file) {
        return new Database(file, 4096);
    }

    public static Database open(Path file, int bufferPages) {
        return new Database(file, bufferPages);
    }

    static Table openTable(StorageEngine engine, Catalog.Entry e) {
        if (e.firstPage() == 0)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表的首页号不能为 0");
        Schema s = e.schema();
        return s.fixedLength() ? new FixedTable(engine, s, e.firstPage())
                : new VarTable(engine, s, e.firstPage());
    }

    public StorageEngine engine() { return engine; }

    public synchronized List<String> tableNames() {
        return new ArrayList<>(tables.keySet());
    }

    private String findTableName(String name) {
        for (String k : tables.keySet())
            if (k.equalsIgnoreCase(name)) return k;
        return null;
    }

    public synchronized Table getTable(String name) {
        String key = findTableName(name);
        if (key == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表不存在: " + name);
        return tables.get(key).table();
    }

    public synchronized boolean hasTable(String name) {
        return findTableName(name) != null;
    }

    public synchronized Table createTable(String name, List<Column> columns) {
        if (hasTable(name))
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表已存在: " + name);
        Schema schema = new Schema(name, columns);
        // 建表即分配首页：避免 DML 反复改目录
        int firstPage = engine.allocPage();
        Page p = engine.pool().getPage(firstPage);
        try {
            java.util.Arrays.fill(p.data(), (byte) 0);
            TablePageHeader.init(p.data(), schema.fixedLength() ? Page.Type.FIXED_TABLE : Page.Type.VAR_TABLE);
            if (!schema.fixedLength()) {
                TablePageHeader.freeLow(p.data(), (short) TablePageHeader.HDR_SIZE);
                TablePageHeader.dataStart(p.data(), (short) Page.SIZE);
                TablePageHeader.totalFree(p.data(), Page.SIZE - TablePageHeader.HDR_SIZE);
            }
            p.setDirty(true);
        } finally {
            engine.pool().unpin(firstPage, true);
        }
        Catalog.Entry entry = new Catalog.Entry(name, schema, firstPage, List.of());
        Table table = schema.fixedLength() ? new FixedTable(engine, schema, firstPage)
                : new VarTable(engine, schema, firstPage);
        tables.put(name, new TableEntry(entry, table));
        persistCatalog();
        engine.flush(); // DDL 直接落盘（DDL 不走事务日志）
        return table;
    }

    public synchronized void dropTable(String name) {
        String key = findTableName(name);
        TableEntry te = key == null ? null : tables.remove(key);
        if (te == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表不存在: " + name);
        // 先释放该表索引的 B+ 树页
        for (IndexEntry ie : indexesFor(name)) {
            ie.tree().drop();
            indexes.remove(ie.meta().name());
        }
        // 释放数据页
        int cur = te.meta().firstPage();
        while (cur != 0) {
            int next = engine.pool().withPage(cur, false,
                    p -> TablePageHeader.nextPage(p.data()));
            engine.freePage(cur);
            cur = next;
        }
        persistCatalog();
    }

    // ---------- 索引 ----------

    /** 建索引：对现有数据全表扫描构建 B+ 树（唯一索引），并登记到目录持久化。 */
    private String findIndexName(String indexName) {
        for (String k : indexes.keySet())
            if (k.equalsIgnoreCase(indexName)) return k;
        return null;
    }

    public synchronized IndexEntry createIndex(String indexName, String tableName, String columnName) {
        if (findIndexName(indexName) != null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "索引已存在: " + indexName);
        String tkey = findTableName(tableName);
        TableEntry te = tkey == null ? null : tables.get(tkey);
        if (te == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表不存在: " + tableName);
        int ci = te.meta().schema().columnIndex(columnName);
        if (ci < 0)
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "表 " + tableName + " 没有列 " + columnName);
        Column col = te.meta().schema().columns().get(ci);
        boolean strKey = col.type() == ColumnType.VARCHAR;
        if (!strKey && col.type() != ColumnType.INT && col.type() != ColumnType.BIGINT)
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "索引列仅支持 INT/BIGINT/VARCHAR(n≤" + BPlusTree.MAX_KEY_BYTES + "): " + columnName);
        if (strKey && (col.maxLength() <= 0 || col.maxLength() > BPlusTree.MAX_KEY_BYTES))
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "VARCHAR 索引键需 ≤" + BPlusTree.MAX_KEY_BYTES + " 字节: " + columnName
                            + "(" + col.maxLength() + ")");
        String keyType = strKey ? IndexMeta.KEY_STRING : IndexMeta.KEY_LONG;
        BPlusTree tree = new BPlusTree(engine, 0);
        for (var it = te.table().scan(); it.hasNext(); ) {
            Row r = it.next();
            Object v = r.values()[ci];
            Object key = IndexKeys.encode(keyType, v);
            if (key == null) continue; // NULL 不入索引
            if (!IndexKeys.insert(tree, keyType, v, r.rid()))
                throw new MiniDbException(MiniDbException.Code.EXEC,
                        "列 " + columnName + " 存在重复值 " + key + "，无法建唯一索引");
        }
        IndexEntry entry = new IndexEntry(tableName,
                new IndexMeta(indexName, columnName, tree.rootPage(), keyType), tree);
        indexes.put(indexName, entry);
        // 更新该表的目录条目
        TableEntry updated = new TableEntry(new Catalog.Entry(tableName, te.meta().schema(),
                te.meta().firstPage(), append(te.meta().indexes(), entry.meta())), te.table());
        tables.put(tableName, updated);
        persistCatalog();
        return entry;
    }

    public synchronized void dropIndex(String indexName) {
        String key = findIndexName(indexName);
        IndexEntry ie = key == null ? null : indexes.remove(key);
        if (ie == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "索引不存在: " + indexName);
        // 释放 B+ 树所有节点页
        ie.tree().drop();
        TableEntry te = tables.get(ie.tableName());
        List<IndexMeta> remaining = new ArrayList<>();
        for (IndexMeta im : te.meta().indexes())
            if (!im.name().equals(indexName)) remaining.add(im);
        tables.put(ie.tableName(), new TableEntry(new Catalog.Entry(
                ie.tableName(), te.meta().schema(), te.meta().firstPage(), remaining), te.table()));
        persistCatalog();
    }

    public synchronized BPlusTree getIndex(String indexName) {
        String key = findIndexName(indexName);
        if (key == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "索引不存在: " + indexName);
        return indexes.get(key).tree();
    }

    public synchronized List<IndexEntry> indexesFor(String tableName) {
        List<IndexEntry> out = new ArrayList<>();
        for (IndexEntry ie : indexes.values())
            if (ie.tableName().equalsIgnoreCase(tableName)) out.add(ie);
        return out;
    }

    public synchronized List<String> indexNames() {
        return new ArrayList<>(indexes.keySet());
    }

    private static List<IndexMeta> append(List<IndexMeta> list, IndexMeta add) {
        List<IndexMeta> out = new ArrayList<>(list);
        out.add(add);
        return out;
    }

    private void persistCatalog() {
        List<Catalog.Entry> entries = new ArrayList<>();
        for (TableEntry te : tables.values()) entries.add(te.meta());
        catalog.save(entries);
    }

    public void flush() {
        engine.flush();
    }

    @Override
    public synchronized void close() {
        // 正常关闭 = checkpoint：数据全部落盘，日志截断
        engine.flush();
        wal.truncate();
        wal.close();
        engine.close();
    }
}
