package minidb.storage;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog + Database：建删表、持久化、故障注入 */
class DatabaseTest {
    @TempDir
    Path dir;

    private Database db(String name, int buf) {
        return Database.open(dir.resolve(name), buf);
    }

    @Test
    void createAndListTables() {
        try (Database d = db("a.db", 64)) {
            d.createTable("t1", List.of(Column.fixed("id", ColumnType.INT)));
            d.createTable("t2", List.of(Column.fixed("id", ColumnType.INT)));
            assertEquals(List.of("t1", "t2"), d.tableNames());
        }
    }

    @Test
    void duplicateTableThrows() {
        try (Database d = db("b.db", 64)) {
            d.createTable("t", List.of(Column.fixed("id", ColumnType.INT)));
            assertThrows(MiniDbException.class,
                    () -> d.createTable("t", List.of(Column.fixed("id", ColumnType.INT))));
        }
    }

    @Test
    void getMissingTableThrows() {
        try (Database d = db("c.db", 64)) {
            assertFalse(d.hasTable("nope"));
            assertThrows(MiniDbException.class, () -> d.getTable("nope"));
        }
    }

    @Test
    void catalogPersistsAcrossReopen() {
        try (Database d = db("d.db", 64)) {
            d.createTable("people", List.of(
                    Column.fixed("id", ColumnType.INT),
                    new Column("name", ColumnType.VARCHAR, 32)));
            d.getTable("people").insert(new Object[]{1, "张三"});
        }
        try (Database d = db("d.db", 64)) {
            assertEquals(List.of("people"), d.tableNames());
            Table t = d.getTable("people");
            assertEquals(1, t.rowCount());
            Object[] row = t.scan().next().values();
            assertEquals(1, row[0]);
            assertEquals("张三", row[1]);
        }
    }

    @Test
    void dropTableFreesPagesAndCatalog() {
        try (Database d = db("e.db", 64)) {
            d.createTable("t", List.of(Column.fixed("id", ColumnType.INT)));
            Table t = d.getTable("t");
            for (int i = 0; i < 100; i++) t.insert(new Object[]{i});
            int firstPage = 3; // 0=meta 1=catalog 2=位图 3=首页
            int pagesBefore = d.engine().pageCount();
            d.dropTable("t");
            assertEquals(List.of(), d.tableNames());
            assertThrows(MiniDbException.class, () -> d.getTable("t"));
            // 高水位不变，但释放的页已可重新分配
            assertEquals(pagesBefore, d.engine().pageCount());
            assertFalse(d.engine().isAllocated(firstPage));
            d.createTable("t2", List.of(Column.fixed("id", ColumnType.INT)));
            // t2 应复用刚释放的页 3 作为首页
            Rid rid = d.getTable("t2").insert(new Object[]{1});
            assertEquals(firstPage, rid.pageId());
        }
    }

    @Test
    void dropMissingTableThrows() {
        try (Database d = db("f.db", 64)) {
            assertThrows(MiniDbException.class, () -> d.dropTable("nope"));
        }
    }

    @Test
    void manyTablesCatalogChain() {
        // 目录条目多到超过一页（>4085 字节），验证页链
        try (Database d = db("g.db", 128)) {
            for (int i = 0; i < 80; i++) {
                d.createTable("table_with_longer_name_" + i, List.of(
                        Column.fixed("id", ColumnType.INT),
                        new Column("name", ColumnType.VARCHAR, 40)));
            }
        }
        try (Database d = db("g.db", 128)) {
            assertEquals(80, d.tableNames().size());
            for (int i = 0; i < 80; i++) {
                Table t = d.getTable("table_with_longer_name_" + i);
                assertEquals(0, t.rowCount());
            }
        }
    }

    @Test
    void catalogSurvivesTableWithLongColumnNames() {
        try (Database d = db("h.db", 64)) {
            d.createTable("t", List.of(
                    new Column("a_very_long_column_name_for_testing", ColumnType.VARCHAR, 100),
                    Column.fixed("x", ColumnType.BIGINT)));
        }
        try (Database d = db("h.db", 64)) {
            assertTrue(d.hasTable("t"));
        }
    }

    @Test
    void fixedAndVarTablesCoexist() {
        try (Database d = db("i.db", 128)) {
            Table fixed = d.createTable("fx", List.of(Column.fixed("a", ColumnType.INT)));
            Table var = d.createTable("vr", List.of(
                    Column.fixed("a", ColumnType.INT),
                    new Column("s", ColumnType.VARCHAR, 10)));
            assertTrue(((FixedTable) fixed).fixedLayout());
            assertFalse(((VarTable) var).fixedLayout());
            fixed.insert(new Object[]{1});
            var.insert(new Object[]{1, "s"});
        }
        try (Database d = db("i.db", 128)) {
            assertEquals(1, d.getTable("fx").rowCount());
            assertEquals(1, d.getTable("vr").rowCount());
        }
    }

    @Test
    void dataSurvivesReopenAfterManyTables() {
        try (Database d = db("j.db", 64)) {
            d.createTable("a", List.of(Column.fixed("v", ColumnType.INT)));
            d.createTable("b", List.of(Column.fixed("v", ColumnType.INT)));
            d.getTable("a").insert(new Object[]{11});
            d.getTable("b").insert(new Object[]{22});
            d.dropTable("a");
            d.createTable("c", List.of(Column.fixed("v", ColumnType.INT)));
            d.getTable("c").insert(new Object[]{33});
        }
        try (Database d = db("j.db", 64)) {
            assertEquals(List.of("b", "c"), d.tableNames());
            assertEquals(22, d.getTable("b").scan().next().values()[0]);
            assertEquals(33, d.getTable("c").scan().next().values()[0]);
        }
    }

    @Test
    void smallBufferPoolStillCorrect() {
        // 缓冲池只有 3 页时也要正确（淘汰压力大）
        try (Database d = db("k.db", 3)) {
            Table t = d.createTable("t", List.of(
                    Column.fixed("a", ColumnType.INT),
                    new Column("s", ColumnType.VARCHAR, 50)));
            for (int i = 0; i < 300; i++) t.insert(new Object[]{i, "s" + i});
            assertEquals(300, t.rowCount());
            int n = 0;
            for (var it = t.scan(); it.hasNext(); ) {
                Row r = it.next();
                assertEquals(n, r.values()[0]);
                n++;
            }
            assertEquals(300, n);
        }
    }
}
