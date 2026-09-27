package minidb.storage;

import minidb.common.MiniDbException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据库门面：打开文件、管理目录与所有表。所有页访问都经过 BufferPool。 */
public final class Database implements AutoCloseable {
    private final StorageEngine engine;
    private final Catalog catalog;
    private final Map<String, TableEntry> tables = new LinkedHashMap<>();

    private record TableEntry(Catalog.Entry meta, Table table) {}

    private Database(Path file, int bufferPages) {
        this.engine = new StorageEngine(file, bufferPages);
        this.catalog = new Catalog(engine);
        for (Catalog.Entry e : catalog.load()) {
            tables.put(e.name(), new TableEntry(e, openTable(engine, e)));
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
        // 释放数据页
        int cur = te.meta().firstPage();
        while (cur != 0) {
            int next = engine.pool().withPage(cur, false,
                    p -> TablePageHeader.nextPage(p.data()));
            engine.freePage(cur);
            cur = next;
        }
        // 阶段2：同时释放索引页
        persistCatalog();
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
