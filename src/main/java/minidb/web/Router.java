package minidb.web;

import com.sun.net.httpserver.HttpExchange;
import minidb.web.session.WebSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 极简路由：method + 路径模板 → 处理器。Controller 只做参数校验，业务在 Service。 */
public final class Router {
    public record Request(WebSession session, Map<String, String> params,
                          Map<String, String> query, String body, HttpExchange exchange) {
        public Map<String, Object> jsonBody() {
            return Json.readMap(body);
        }

        public String param(String name) {
            return params.get(name);
        }

        public String q(String name) {
            return query.get(name);
        }
    }

    public interface Handler {
        Object handle(Request req) throws Exception;
    }

    public record Route(String method, Pattern pattern, List<String> names, Handler handler) {}

    public record Match(Route route, Matcher matcher) {}

    private final List<Route> routes = new ArrayList<>();

    public void add(String method, String pathTemplate, Handler handler) {
        // {name} → 按序编号捕获组（Java 17 无 namedGroups，自行记录名字顺序）
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pathTemplate.length()) {
            char c = pathTemplate.charAt(i);
            if (c == '{') {
                int end = pathTemplate.indexOf('}', i);
                names.add(pathTemplate.substring(i + 1, end));
                regex.append("([^/]+)");
                i = end + 1;
            } else {
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        routes.add(new Route(method, Pattern.compile(regex.toString()), names, handler));
    }

    public Match find(String method, String path) {
        Match pathMatch = null;
        for (Route r : routes) {
            Matcher m = r.pattern().matcher(path);
            if (!m.matches()) continue;
            if (r.method().equals(method)) return new Match(r, m);
            if (pathMatch == null) pathMatch = new Match(r, m);
        }
        return pathMatch; // 供 405 判定；调用方再核对 method
    }

    /** 提取路径参数（按 {name} 顺序取编号组）。 */
    public static Map<String, String> extractParams(Route route, Matcher m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < route.names().size(); i++)
            out.put(route.names().get(i), m.group(i + 1));
        return out;
    }

    /** 解析查询串（UTF-8）。 */
    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(urlDecode(k), urlDecode(v));
        }
        return out;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
