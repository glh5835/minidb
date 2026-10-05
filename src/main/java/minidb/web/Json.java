package minidb.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minidb.common.MiniDbException;
import minidb.storage.Column;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 工具：统一 mapper + 行值与 JSON 的双向转换（BIGINT 走字符串，计划书 §35）。 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static Map<String, Object> readMap(String body) {
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new Api.ApiException("VALIDATION_ERROR", "请求体不是合法 JSON: " + e.getMessage());
        }
    }

    /** 内核行值 → JSON 值：BIGINT(Long) 字符串化避免 JS 精度丢失，其余原样。 */
    public static Object toJsonValue(Object v) {
        if (v instanceof Long) return String.valueOf(v);
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) return String.valueOf(d);
            return d;
        }
        return v;
    }

    /** JSON 请求值 → 列存储值（INT/BIGINT/DOUBLE/VARCHAR），类型不符给出可读错误。 */
    public static Object fromJsonValue(JsonNode node, Column col) {
        if (node == null || node.isNull()) return null;
        return switch (col.type()) {
            case INT -> {
                if (node.isInt() || node.isLong()) yield node.intValue();
                if (node.isTextual()) yield parseInt(node.asText(), col);
                throw typeErr(col, node);
            }
            case BIGINT -> {
                if (node.isInt() || node.isLong()) yield node.longValue();
                if (node.isTextual()) yield parseLong(node.asText(), col);
                if (node.isNumber()) yield node.longValue();
                throw typeErr(col, node);
            }
            case DOUBLE -> {
                if (node.isNumber()) yield node.doubleValue();
                if (node.isTextual()) {
                    try {
                        yield Double.parseDouble(node.asText().trim());
                    } catch (NumberFormatException e) {
                        throw typeErr(col, node);
                    }
                }
                throw typeErr(col, node);
            }
            case VARCHAR -> {
                if (node.isTextual()) yield node.asText();
                if (node.isValueNode()) yield node.asText();
                throw typeErr(col, node);
            }
        };
    }

    /** JsonNode 对象 → 按列序的行值数组。要求提供全部列（与 .insert 语义一致）。 */
    public static Object[] rowValues(JsonNode obj, List<Column> columns) {
        Object[] out = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            String key = c.name();
            JsonNode v = obj.has(key) ? obj.get(key) : null;
            if (v == null) {
                // 大小写不敏感兜底
                var it = obj.fieldNames();
                while (it.hasNext()) {
                    String f = it.next();
                    if (f.equalsIgnoreCase(key)) {
                        v = obj.get(f);
                        break;
                    }
                }
            }
            out[i] = v == null ? null : fromJsonValue(v, c);
        }
        return out;
    }

    private static int parseInt(String s, Column col) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw typeErr(col, s);
        }
    }

    private static long parseLong(String s, Column col) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw typeErr(col, s);
        }
    }

    private static Api.ApiException typeErr(Column col, Object v) {
        return new Api.ApiException("VALIDATION_ERROR",
                "列 " + col.name() + "(" + col.type() + ") 不接受值: " + v);
    }

    /** 行 → Map（带 rid）。 */
    public static Map<String, Object> rowToMap(minidb.storage.Schema schema, Object[] row, String rid) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < schema.columns().size(); i++)
            m.put(schema.columns().get(i).name(), toJsonValue(row[i]));
        if (rid != null) m.put("_rid", rid);
        return m;
    }

    /** Rid ↔ 字符串 "page/slot"。 */
    public static String ridToString(minidb.common.Rid rid) {
        return rid.pageId() + "/" + rid.slot();
    }

    public static minidb.common.Rid ridFromString(String s) {
        int slash = s.indexOf('/');
        if (slash <= 0)
            throw new Api.ApiException("VALIDATION_ERROR", "RID 格式应为 page/slot: " + s);
        try {
            return new minidb.common.Rid(Integer.parseInt(s.substring(0, slash)),
                    Integer.parseInt(s.substring(slash + 1)));
        } catch (NumberFormatException e) {
            throw new Api.ApiException("VALIDATION_ERROR", "RID 格式应为 page/slot: " + s);
        }
    }

    /** 安全取字符串参数。 */
    public static String str(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int intVal(Object v, int def) {
        if (v instanceof Number num) return num.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public static boolean boolVal(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    /** 字符串列表（JSON 数组元素转字符串）。 */
    public static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list)
            for (Object o : list) out.add(String.valueOf(o));
        return out;
    }
}
