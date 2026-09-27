package minidb.storage;

import minidb.btree.BPlusTree;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Database 索引集成：建索引/删索引/持久化/故障注入 */
class DatabaseIndexTest {
    @TempDir
    Path dir;

    private Database db(String name, int buf) {
        return Database.open(dir.resolve(name), buf);
    }

    private void seed(Database d) {
        Table t = d.createTable("emp", List.of(
                Column.fixed("id", ColumnType.INT),
                Column.fixed("dept", ColumnType.INT),
                new Column("name", ColumnType.VARCHAR, 20)));
        for (int i = 0; i < 500; i++)
            t.insert(new Object[]{i, i % 10, "n" + i});
    }

    @Test
    void createIndexBuildsFromExistingRows() {
        try (Database d = db("a.db", 128)) {
            seed(d);
            d.createIndex("idx_id", "emp", "id");
            BPlusTree tree = d.getIndex("idx_id");
            assertEquals(500, tree.stats().totalKeys());
            assertEquals(new Rid(3, 7), tree.search(7));
            assertNull(tree.search(500));
        }
    }

    @Test
    void indexPersistsAcrossReopen() {
        try (Database d = db("b.db", 128)) {
            seed(d);
            d.createIndex("idx_id", "emp", "id");
        }
        try (Database d = db("b.db", 128)) {
            assertEquals(List.of("idx_id"), d.indexNames());
            BPlusTree tree = d.getIndex("idx_id");
            assertEquals(500, tree.stats().totalKeys());
            assertEquals(new Rid(3, 42), tree.search(42));
        }
    }

    @Test
    void duplicateIndexNameThrows() {
        try (Database d = db("c.db", 64)) {
            seed(d);
            d.createIndex("i1", "emp", "id");
            assertThrows(MiniDbException.class, () -> d.createIndex("i1", "emp", "dept"));
        }
    }

    @Test
    void duplicateColumnValuesRejected() {
        try (Database d = db("d.db", 64)) {
            seed(d); // dept 列只有 0..9 → 重复
            assertThrows(MiniDbException.class, () -> d.createIndex("idx_dept", "emp", "dept"));
        }
    }

    @Test
    void indexOnMissingTableOrColumnThrows() {
        try (Database d = db("e.db", 64)) {
            seed(d);
            assertThrows(MiniDbException.class, () -> d.createIndex("i1", "nope", "id"));
            assertThrows(MiniDbException.class, () -> d.createIndex("i1", "emp", "nope"));
        }
    }

    @Test
    void dropIndexFreesPages() {
        try (Database d = db("f.db", 128)) {
            seed(d);
            d.createIndex("idx_id", "emp", "id");
            int pagesWithIndex = d.engine().pageCount();
            d.dropIndex("idx_id");
            assertThrows(MiniDbException.class, () -> d.getIndex("idx_id"));
            // 释放后可重新建索引并复用页
            d.createIndex("idx_id2", "emp", "id");
            assertTrue(d.engine().pageCount() <= pagesWithIndex);
        }
    }

    @Test
    void dropMissingIndexThrows() {
        try (Database d = db("g.db", 64)) {
            assertThrows(MiniDbException.class, () -> d.dropIndex("nope"));
        }
    }

    @Test
    void indexesForFiltersByTable() {
        try (Database d = db("h.db", 128)) {
            seed(d);
            d.createTable("other", List.of(Column.fixed("k", ColumnType.BIGINT)));
            d.createIndex("i_emp", "emp", "id");
            d.createIndex("i_other", "other", "k");
            assertEquals(1, d.indexesFor("emp").size());
            assertEquals("i_emp", d.indexesFor("emp").get(0).meta().name());
        }
    }

    @Test
    void dropTableDropsItsIndexes() {
        try (Database d = db("i.db", 128)) {
            seed(d);
            d.createIndex("idx_id", "emp", "id");
            d.dropTable("emp");
            assertEquals(List.of(), d.indexNames());
            assertThrows(MiniDbException.class, () -> d.getIndex("idx_id"));
        }
    }

    @Test
    void bigintColumnIndex() {
        try (Database d = db("j.db", 128)) {
            Table t = d.createTable("b", List.of(
                    Column.fixed("v", ColumnType.BIGINT)));
            for (long i = 0; i < 300; i++) t.insert(new Object[]{i * 1_000_000_000L});
            d.createIndex("ib", "b", "v");
            assertEquals(new Rid(3, 42), d.getIndex("ib").search(42_000_000_000L));
        }
    }
}
