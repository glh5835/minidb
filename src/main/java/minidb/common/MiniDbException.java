package minidb.common;

/** MiniDB 统一异常。code 用于区分错误类别，方便测试做故障注入断言。 */
public class MiniDbException extends RuntimeException {
    public enum Code {
        IO, PAGE_INVALID, BUFFER_FULL, SCHEMA, RECORD, CATALOG, BTREE,
        PARSE, EXEC, TXN, DEADLOCK, LOCK, WAL, JDBC, UNIQUE
    }

    public final Code code;

    public MiniDbException(Code code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }

    public MiniDbException(Code code, String message, Throwable cause) {
        super("[" + code + "] " + message, cause);
        this.code = code;
    }
}
