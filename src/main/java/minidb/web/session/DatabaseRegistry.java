package minidb.web.session;

import minidb.storage.Database;
import minidb.web.Api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库注册表：同一路径在 JVM 内只打开一个 Database 实例（计划书 §15），
 * 避免多个 Database 对象同时写同一文件。引用计数归零后物理关闭。
 */
public final class DatabaseRegistry implements AutoCloseable {
    public static final class Entry {
        public final Path path;
        public final Database db;
        int refs;

        Entry(Path path, Database db) {
            this.path = path;
            this.db = db;
        }
    }

    private final Map<String, Entry> open = new LinkedHashMap<>();

    private static String key(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    /** 数据库文件是否存在。 */
    public static boolean exists(String pathStr) {
        return Files.isRegularFile(Path.of(pathStr));
    }

    /**
     * 打开（或复用已打开）数据库。创建场景先建空文件再走 open（Database.open 不创建文件）。
     * 返回的 Entry 引用计数 +1；调用方不再使用时必须调用 release。
     */
    public synchronized Entry acquire(String pathStr, boolean createIfMissing) {
        Path p = Path.of(pathStr).toAbsolutePath().normalize();
        String k = key(p);
        Entry e = open.get(k);
        if (e == null) {
            if (!Files.isRegularFile(p)) {
                if (!createIfMissing)
                    throw new Api.ApiException("DATABASE_NOT_FOUND", "数据库文件不存在: " + p);
                try {
                    Files.createDirectories(p.getParent() == null ? Path.of(".") : p.getParent());
                    Files.createFile(p);
                } catch (IOException ioe) {
                    throw new Api.ApiException("IO_ERROR", "无法创建数据库文件: " + p);
                }
            }
            e = new Entry(p, Database.open(p));
            open.put(k, e);
        }
        e.refs++;
        return e;
    }

    /** 引用计数 -1；归零即物理关闭（checkpoint：flush + WAL truncate）。 */
    public synchronized void release(Entry e) {
        e.refs--;
        if (e.refs <= 0) {
            open.remove(key(e.path));
            e.db.close();
        }
    }

    /** 崩溃实验专用：不 flush 直接断电关闭（真实 crash 语义），并从注册表移除。 */
    public synchronized void crash(Entry e) {
        open.remove(key(e.path));
        e.db.crash();
        e.refs = 0;
    }

    /** 崩溃后条目已失效：仅从注册表移除，不再触碰数据库句柄。 */
    public synchronized void discardEntry(Entry e) {
        open.remove(key(e.path));
        e.refs = 0;
    }

    /** 数据库文件是否正被本 JVM 打开。 */
    public synchronized boolean isOpen(String pathStr) {
        return open.containsKey(key(Path.of(pathStr).toAbsolutePath().normalize()));
    }

    public synchronized List<Entry> openDatabases() {
        return new ArrayList<>(open.values());
    }

    @Override
    public synchronized void close() {
        List<Entry> all = new ArrayList<>(open.values());
        open.clear();
        for (Entry e : all) e.db.close();
    }
}
