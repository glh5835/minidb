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

    public synchronized Table getTable(String name) {
        TableEntry te = tables.get(name);
        if (te == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表不存在: " + name);
        return te.table();
    }

    public synchronized boolean hasTable(String name) {
        return tables.containsKey(name);
    }

    public synchronized Table createTable(String name, List<Column> columns) {
        if (tables.containsKey(name))
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
        return table;
    }

    public synchronized void dropTable(String name) {
        TableEntry te = tables.remove(name);
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
    public synchronized IndexEntry createIndex(String indexName, String tableName, String columnName) {
        if (indexes.containsKey(indexName))
            throw new MiniDbException(MiniDbException.Code.CATALOG, "索引已存在: " + indexName);
        TableEntry te = tables.get(tableName);
        if (te == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表不存在: " + tableName);
        int ci = te.meta().schema().columnIndex(columnName);
        if (ci < 0)
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "表 " + tableName + " 没有列 " + columnName);
        Column col = te.meta().schema().columns().get(ci);
        if (col.type() != ColumnType.INT && col.type() != ColumnType.BIGINT)
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "索引列仅支持 INT/BIGINT: " + columnName);
        BPlusTree tree = new BPlusTree(engine, 0);
        for (var it = te.table().scan(); it.hasNext(); ) {
            Row r = it.next();
            Object v = r.values()[ci];
            long key = ((Number) v).longValue();
            if (!tree.insert(key, r.rid()))
                throw new MiniDbException(MiniDbException.Code.EXEC,
                        "列 " + columnName + " 存在重复值 " + key + "，无法建唯一索引");
        }
        IndexEntry entry = new IndexEntry(tableName, new IndexMeta(indexName, columnName, tree.rootPage()), tree);
        indexes.put(indexName, entry);
        // 更新该表的目录条目
        TableEntry updated = new TableEntry(new Catalog.Entry(tableName, te.meta().schema(),
                te.meta().firstPage(), append(te.meta().indexes(), entry.meta())), te.table());
        tables.put(tableName, updated);
        persistCatalog();
        return entry;
    }

    public synchronized void dropIndex(String indexName) {
        IndexEntry ie = indexes.remove(indexName);
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
        IndexEntry ie = indexes.get(indexName);
        if (ie == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "索引不存在: " + indexName);
        return ie.tree();
    }

    public synchronized List<IndexEntry> indexesFor(String tableName) {
        List<IndexEntry> out = new ArrayList<>();
        for (IndexEntry ie : indexes.values())
            if (ie.tableName().equals(tableName)) out.add(ie);
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
        engine.close();
    }
}
