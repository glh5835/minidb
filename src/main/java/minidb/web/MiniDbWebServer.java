package minidb.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minidb.common.MiniDbException;
import minidb.web.session.DatabaseRegistry;
import minidb.web.session.SessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * MiniDB Studio Web 服务器（JDK 17 HttpServer + Jackson）。
 * 默认只监听 127.0.0.1（计划书 §42：可浏览/修改/删除数据的服务绝不对公网开放）。
 *
 * 职责分层：HTTP → 参数校验 → Session → Service → 真实内核。
 * 正式构建时前端静态资源打进 classpath /webroot，由本服务器直接提供。
 */
public final class MiniDbWebServer {
    private final int port;
    private final Path dataDir;
    private final Path webDir;   // 开发模式：前端构建产物目录；null = classpath /webroot
    private final boolean devMode;
    private HttpServer server;
    private DatabaseRegistry registry;
    private SessionManager sessions;
    private MiniDbService service;
    private LabService labs;

    public MiniDbWebServer(int port, Path dataDir, Path webDir, boolean devMode) {
        this.port = port;
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.webDir = webDir == null ? null : webDir.toAbsolutePath().normalize();
        this.devMode = devMode;
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        Path dataDir = Path.of("").toAbsolutePath();
        Path webDir = null;
        boolean dev = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data-dir" -> dataDir = Path.of(args[++i]);
                case "--web-dir" -> webDir = Path.of(args[++i]);
                case "--dev" -> dev = true;
                case "--help", "-h" -> {
                    System.out.println("MiniDB Studio — 用法: java minidb.web.MiniDbWebServer "
                            + "[--port 8080] [--data-dir .] [--web-dir frontend/dist] [--dev]");
                    return;
                }
                default -> System.err.println("未知参数: " + args[i]);
            }
        }
        System.out.println("MiniDB Studio 启动中… 数据目录: " + dataDir);
        MiniDbWebServer ws = new MiniDbWebServer(port, dataDir, webDir, dev);
        ws.start();
        System.out.println("就绪：http://127.0.0.1:" + port);
    }

    public void start() throws IOException {
        registry = new DatabaseRegistry();
        sessions = new SessionManager(registry);
        service = new MiniDbService(registry, sessions, devMode, dataDir);
        labs = new LabService(registry, dataDir);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server = s;
        s.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "minidb-studio-http");
            t.setDaemon(false);
            return t;
        }));
        s.createContext("/", this::dispatch);
        s.start();
    }

    public int port() {
        return port;
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (sessions != null) sessions.close();
        if (registry != null) registry.close();
    }

    // ---------------- 路由注册 ----------------

    private void dispatch(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/")) {
                handleApi(ex, method, path);
            } else if ("GET".equals(method)) {
                serveStatic(ex, path);
            } else {
                sendJson(ex, 405, Api.fail(new Api.ApiException("METHOD_NOT_ALLOWED", "不支持的请求方法"), devMode));
            }
        } catch (Exception e) {
            sendJson(ex, 500, Api.fail(new Api.ApiException("INTERNAL_ERROR",
                    "服务器内部错误: " + e.getMessage()), devMode));
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String method, String path) throws IOException {
        if (!path.startsWith("/api/v1/")) {
            sendJson(ex, 404, Api.fail(new Api.ApiException("NOT_FOUND", "未知 API 路径"), devMode));
            return;
        }
        String rel = path.substring("/api/v1".length());
        Router.Match m = router.find(method, rel);
        if (m == null) {
            Router.Match any = router.find("ANY_PROBE", rel);
            if (any != null) {
                sendJson(ex, 405, Api.fail(new Api.ApiException("METHOD_NOT_ALLOWED",
                        method + " 不适用于 " + rel), devMode));
            } else {
                sendJson(ex, 404, Api.fail(new Api.ApiException("NOT_FOUND", "未知 API: " + rel), devMode));
            }
            return;
        }
        try {
            boolean sessionless = rel.equals("/health") || rel.startsWith("/labs/")
                    || (method.equals("POST") && rel.equals("/sessions"));
            Map<String, String> query = Router.parseQuery(ex.getRequestURI().getRawQuery());
            String sid = resolveSessionId(ex, query);
            minidb.web.session.WebSession session = sessionless ? null : sessions.require(sid);
            String body = readBody(ex);
            Map<String, String> params = Router.extractParams(m.route(), m.matcher());
            Router.Request req = new Router.Request(session, params, query, body, ex);
            Object result;
            if (sessionless && rel.equals("/sessions")) {
                // 创建会话
                minidb.web.session.WebSession s = sessions.create();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sessionId", s.id);
                data.put("dbPath", s.dbPath);
                data.put("hasDb", s.hasDb());
                data.put("txn", service.txnState(s));
                result = Api.ok(data);
                // 会话标识同时以 Cookie 下发（部分内嵌浏览器会剥离自定义请求头）
                ex.getResponseHeaders().add("Set-Cookie",
                        "minidb-session=" + s.id + "; Path=/; HttpOnly; SameSite=Lax");
            } else {
                result = Api.ok(m.route().handler().handle(req));
            }
            sendJson(ex, 200, result);
        } catch (Api.ApiException e) {
            sendJson(ex, e.httpStatus, Api.fail(e, devMode));
        } catch (MiniDbException e) {
            Api.ApiException ae = Api.map(e);
            sendJson(ex, ae.httpStatus, Api.fail(ae, devMode));
        } catch (Exception e) {
            sendJson(ex, 500, Api.fail(new Api.ApiException("INTERNAL_ERROR",
                    "服务器内部错误: " + e.getMessage()), devMode));
        }
    }

    private final Router router = buildRouter();

    private Router buildRouter() {
        Router r = new Router();
        // ---- 无需会话 ----
        r.add("GET", "/health", req -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ok");
            data.put("service", "minidb-studio");
            data.put("version", "0.1.0");
            return data; // 服务器统一包 Api.ok
        });
        // 创建会话（handleApi 对 POST /sessions 特判，这里只注册路由让 find 命中）
        r.add("POST", "/sessions", req -> null);
        // ---- 以下路由需要会话（session 由 handleApi 统一注入） ----
        r.add("GET", "/session", req -> service.sessionState(req.session()));
        r.add("DELETE", "/session", req -> {
            Map<String, Object> data = service.sessionState(req.session());
            sessions.close(req.session().id);
            data.put("closed", true);
            return data;
        });
        // 数据库
        r.add("POST", "/databases/create", req -> service.createDatabase(req.session(),
                Json.str(req.jsonBody(), "path", "")));
        r.add("POST", "/databases/open", req -> service.openDatabase(req.session(),
                Json.str(req.jsonBody(), "path", "")));
        r.add("POST", "/databases/close", req -> service.closeDatabase(req.session()));
        r.add("POST", "/databases/check", req -> service.checkDatabase(req.jsonBody()));
        r.add("POST", "/databases/sample", req -> service.createSampleDatabase(req.session(),
                Json.str(req.jsonBody(), "dir", "")));
        // 表
        r.add("GET", "/tables", req -> service.tables(req.session()));
        r.add("POST", "/tables", req -> service.createTable(req.session(), req.jsonBody()));
        r.add("GET", "/tables/{name}/schema", req -> service.schema(req.session(), req.param("name")));
        r.add("GET", "/tables/{name}/rows", req -> service.rows(req.session(), req.param("name"), req.query()));
        r.add("POST", "/tables/{name}/rows", req -> service.insertRow(req.session(), req.param("name"),
                Json.MAPPER.valueToTree(req.jsonBody())));
        r.add("PUT", "/tables/{name}/rows/{page}/{slot}", req -> service.updateRow(req.session(),
                req.param("name"), Integer.parseInt(req.param("page")), Integer.parseInt(req.param("slot")),
                Json.MAPPER.valueToTree(req.jsonBody())));
        r.add("DELETE", "/tables/{name}/rows/{page}/{slot}", req -> service.deleteRow(req.session(),
                req.param("name"), Integer.parseInt(req.param("page")), Integer.parseInt(req.param("slot"))));
        r.add("DELETE", "/tables/{name}", req -> service.dropTable(req.session(), req.param("name")));
        // SQL
        r.add("POST", "/sql/execute", req -> {
            var body = req.jsonBody();
            Integer only = body.get("onlyIndex") instanceof Number n ? n.intValue() : null;
            return service.executeSql(req.session(), Json.str(body, "sql", ""), only,
                    MiniDbService.MAX_RESULT_ROWS, true);
        });
        r.add("POST", "/sql/plan", req -> service.plan(req.session(), Json.str(req.jsonBody(), "sql", "")));
        r.add("POST", "/sql/pipeline", req -> service.sqlPipeline(req.session(),
                Json.str(req.jsonBody(), "sql", "")));
        // 事务
        r.add("GET", "/transactions", req -> service.txnState(req.session()));
        r.add("POST", "/transactions/begin", req -> service.begin(req.session(), req.jsonBody()));
        r.add("POST", "/transactions/commit", req -> service.commit(req.session()));
        r.add("POST", "/transactions/rollback", req -> service.rollback(req.session()));
        // 索引
        r.add("GET", "/indexes", req -> service.indexes(req.session(), req.q("table")));
        r.add("POST", "/indexes", req -> service.createIndex(req.session(), req.jsonBody()));
        r.add("DELETE", "/indexes/{name}", req -> service.dropIndex(req.session(), req.param("name")));
        r.add("GET", "/indexes/{name}/btree", req -> service.btreeSnapshot(req.session(), req.param("name")));
        // 快照
        r.add("GET", "/snapshots/buffer-pool", req -> service.bufferPoolSnapshot(req.session()));
        r.add("GET", "/snapshots/locks", req -> service.lockSnapshot(req.session()));
        r.add("GET", "/snapshots/pages", req -> service.pageSnapshot(req.session(), req.q("table")));
        r.add("GET", "/snapshots/wal", req -> service.wal(req.session()));
        r.add("POST", "/snapshots/flush", req -> service.flush(req.session()));
        // 实验室（独立实验库，不依赖会话数据库）
        r.add("POST", "/labs/txn/setup", req -> labs.txnSetup());
        r.add("POST", "/labs/txn/exec", req -> labs.txnExec(req.jsonBody()));
        r.add("GET", "/labs/txn/poll", req -> labs.txnPoll());
        r.add("GET", "/labs/txn/state", req -> labs.txnState());
        r.add("POST", "/labs/recovery/run", req -> labs.recoveryRun());
        r.add("POST", "/labs/storage/setup", req -> labs.storageSetup());
        r.add("GET", "/labs/storage/state", req -> labs.storageState(req.q("table")));
        r.add("POST", "/labs/storage/act", req -> labs.storageAct(req.jsonBody()));
        r.add("POST", "/labs/bp/setup", req -> labs.bufferPoolSetup());
        r.add("GET", "/labs/bp/state", req -> labs.bufferPoolState(req.q("action")));
        r.add("POST", "/labs/bp/act", req -> labs.bufferPoolAct(req.jsonBody()));
        r.add("POST", "/labs/perf/run", req -> labs.perfRun(req.jsonBody()));
        r.add("POST", "/labs/jdbc/run", req -> labs.jdbcRun());
        return r;
    }

    // ---------------- HTTP 基础 ----------------

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 会话标识解析：自定义请求头 → Cookie → 查询参数。
     * 部分内嵌浏览器/代理会剥离自定义头，多通道读取保证所有浏览器都能携带会话。
     */
    private String resolveSessionId(HttpExchange ex, Map<String, String> query) {
        String h = ex.getRequestHeaders().getFirst("X-MiniDB-Session");
        if (h != null && !h.isBlank()) return h.trim();
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String pair : cookie.split(";")) {
                String p = pair.trim();
                if (p.startsWith("minidb-session="))
                    return p.substring("minidb-session=".length()).trim();
            }
        }
        String q = query.get("sessionId");
        return q == null || q.isBlank() ? null : q.trim();
    }

    private void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        // 防路径穿越
        String rel = path.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.contains("..")) {
            sendJson(ex, 400, Api.fail(new Api.ApiException("BAD_REQUEST", "非法路径"), false));
            return;
        }
        byte[] bytes = webDir != null
                ? tryReadFile(webDir.resolve(rel))
                : tryReadResource("/webroot/" + rel);
        if (bytes == null) {
            // SPA 回退到 index.html
            bytes = webDir != null ? tryReadFile(webDir.resolve("index.html"))
                    : tryReadResource("/webroot/index.html");
        }
        if (bytes == null) {
            byte[] msg = "MiniDB Studio：前端资源未构建。请先构建 frontend/ 并将 dist 复制到 src/main/resources/webroot，"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(msg);
            }
            return;
        }
        String type = contentType(rel);
        ex.getResponseHeaders().set("Content-Type", type);
        // index.html 不缓存（引用带 hash 的资源）；带 hash 的资源可长缓存
        if (rel.equals("index.html")) {
            ex.getResponseHeaders().set("Cache-Control", "no-store");
        } else if (rel.startsWith("assets/")) {
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
        }
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private byte[] tryReadFile(Path p) throws IOException {
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    private byte[] tryReadResource(String res) throws IOException {
        try (InputStream in = MiniDbWebServer.class.getResourceAsStream(res)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /** 健康检查用（测试）。 */
    public boolean isRunning() {
        return server != null;
    }

    public List<String> openDbPaths() {
        return registry.openDatabases().stream().map(e -> e.path.toString()).toList();
    }
}
