package minidb.web.session;

import minidb.web.Api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器（计划书 §16）：空闲 15 分钟的会话自动回滚未提交事务并释放；
 * 之后前端再用该会话操作会得到 SESSION_EXPIRED。
 * 浏览器刷新恢复（§43）：前端保存 sessionId，GET /session 查询即可恢复状态。
 */
public final class SessionManager implements AutoCloseable {
    public static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);

    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final DatabaseRegistry registry;
    private final ScheduledExecutorService reaper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "minidb-studio-session-reaper");
                t.setDaemon(true);
                return t;
            });

    public SessionManager(DatabaseRegistry registry) {
        this.registry = registry;
        reaper.scheduleWithFixedDelay(this::reapIdle, 60, 60, TimeUnit.SECONDS);
    }

    public WebSession create() {
        WebSession s = new WebSession(UUID.randomUUID().toString().replace("-", ""));
        sessions.put(s.id, s);
        return s;
    }

    /** 取会话（不存在/已过期抛 SESSION_EXPIRED）；刷新 lastTouch。 */
    public WebSession require(String id) {
        WebSession s = id == null ? null : sessions.get(id);
        if (s == null)
            throw new Api.ApiException("SESSION_EXPIRED", "会话已过期，未提交事务已自动回滚。", 410);
        s.touch();
        return s;
    }

    public WebSession get(String id) {
        return id == null ? null : sessions.get(id);
    }

    /** 绑定/切换会话数据库（同路径全 JVM 单实例）。 */
    public void bindDatabase(WebSession s, String path, boolean createIfMissing) {
        DatabaseRegistry.Entry e = registry.acquire(path, createIfMissing);
        DatabaseRegistry.Entry old = s.dbEntry;
        s.dbPath = e.path.toString();
        s.dbEntry = e;
        s.executor = new minidb.exec.Executor(e.db);
        s.txn = null;
        if (old != null && old != e) registry.release(old);
    }

    /** 显式关闭会话。 */
    public void close(String id) {
        WebSession s = sessions.remove(id);
        if (s != null) {
            s.close();
            if (s.dbEntry != null) registry.release(s.dbEntry);
        }
    }

    /** 空闲回收：回滚事务 → 关会话 → 释放数据库引用（测试也直接调用）。 */
    public void reapIdle() {
        long now = System.currentTimeMillis();
        for (WebSession s : sessions.values()) {
            if (now - s.lastTouch > IDLE_TIMEOUT_MS) {
                sessions.remove(s.id);
                s.close();
                if (s.dbEntry != null) registry.release(s.dbEntry);
            }
        }
    }

    public List<WebSession> all() {
        return new ArrayList<>(sessions.values());
    }

    @Override
    public void close() {
        reaper.shutdownNow();
        List<WebSession> all = new ArrayList<>(sessions.values());
        sessions.clear();
        for (WebSession s : all) {
            s.close();
            if (s.dbEntry != null) registry.release(s.dbEntry);
        }
    }
}
