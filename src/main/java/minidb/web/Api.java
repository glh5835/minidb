package minidb.web;

import minidb.common.MiniDbException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应协议（计划书 §34）：
 * 成功 {"success":true,"data":...,"meta":{...}}；失败 {"success":false,"error":{code,message,...}}。
 * UI 依据 code 决定交互，绝不解析英文异常串。
 */
public final class Api {
    private Api() {}

    /** 业务异常：code 决定前端交互，httpStatus 决定响应码。 */
    public static class ApiException extends RuntimeException {
        public final String code;
        public final int httpStatus;
        public final Integer line;
        public final Integer column;

        public ApiException(String code, String message) {
            this(code, message, 400, null, null);
        }

        public ApiException(String code, String message, int httpStatus) {
            this(code, message, httpStatus, null, null);
        }

        public ApiException(String code, String message, int httpStatus, Integer line, Integer column) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
            this.line = line;
            this.column = column;
        }
    }

    public static ApiException error(String code, String message) {
        return new ApiException(code, message);
    }

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    public static Map<String, Object> ok(Object data, Map<String, Object> meta) {
        Map<String, Object> m = ok(data);
        m.put("meta", meta);
        return m;
    }

    public static Map<String, Object> fail(ApiException e, boolean devMode) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code);
        err.put("message", e.getMessage());
        if (e.line != null) err.put("line", e.line);
        if (e.column != null) err.put("column", e.column);
        if (devMode && e.getCause() != null)
            err.put("details", String.valueOf(e.getCause()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", err);
        return m;
    }

    /**
     * 内核异常 → API 错误码映射（MiniDB-Web-Integration-Notes.md §6）。
     * CATALOG 按消息细分（表/索引/库不存在），RECORD 按唯一索引/RID 场景细分。
     */
    public static ApiException map(MiniDbException e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        // MiniDbException.getMessage 形如 "[CODE] 文本"，剥掉前缀给用户看
        String plain = msg.startsWith("[") && msg.contains("] ") ? msg.substring(msg.indexOf("] ") + 2) : msg;
        String code = switch (e.code) {
            case IO -> "IO_ERROR";
            case PAGE_INVALID -> "PAGE_INVALID";
            case BUFFER_FULL -> "BUFFER_FULL";
            case SCHEMA -> "SCHEMA_ERROR";
            case RECORD -> plain.contains("请刷新后重试") ? "RID_STALE"
                    : plain.contains("唯一") ? "UNIQUE_CONSTRAINT_VIOLATION" : "RECORD_ERROR";
            case CATALOG -> plain.contains("表不存在") ? "TABLE_NOT_FOUND"
                    : plain.contains("索引不存在") ? "INDEX_NOT_FOUND"
                    : plain.contains("未打开数据库") || plain.contains("数据库文件") ? "DATABASE_NOT_OPEN"
                    : "CATALOG_ERROR";
            case BTREE -> "BTREE_ERROR";
            case PARSE -> "SQL_PARSE_ERROR";
            case EXEC -> "SQL_EXECUTION_ERROR";
            case TXN -> plain.contains("已在进行中") ? "TRANSACTION_ACTIVE"
                    : plain.contains("没有活动事务") ? "TRANSACTION_REQUIRED" : "TXN_ERROR";
            case DEADLOCK -> "DEADLOCK";
            case LOCK -> "LOCK_ERROR";
            case WAL -> "WAL_ERROR";
            case JDBC -> "JDBC_ERROR";
            case UNIQUE -> "UNIQUE_CONSTRAINT_VIOLATION";
        };
        return new ApiException(code, plain, 409, null, null);
    }
}
