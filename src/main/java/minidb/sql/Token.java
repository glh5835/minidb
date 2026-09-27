package minidb.sql;

import minidb.common.MiniDbException;

/** SQL 词元。 */
public final class Token {
    public enum Type {
        IDENT, NUMBER, STRING,
        // 运算符
        EQ, NEQ, LT, LE, GT, GE, PLUS, MINUS, STAR, SLASH, PERCENT,
        LPAREN, RPAREN, COMMA, DOT, SEMI,
        // 关键字（大小写不敏感）
        SELECT, FROM, WHERE, AND, OR, NOT, LIKE, IN, IS, NULL,
        JOIN, INNER, LEFT, OUTER, ON, AS,
        ORDER, GROUP, BY, HAVING, LIMIT, OFFSET, DISTINCT,
        INSERT, INTO, VALUES, UPDATE, SET, DELETE,
        CREATE, TABLE, DROP, INDEX, PRIMARY, KEY,
        EXISTS, ASC, DESC, BETWEEN, UNQUOTED_EOF,
        COUNT, SUM, AVG, MIN, MAX,
        INT, INTEGER, BIGINT, LONG, DOUBLE, FLOAT, VARCHAR, STRING_TYPE,
        BEGIN, COMMIT, ROLLBACK,
        EOF
    }

    public final Type type;
    /** 标识符/字符串/数字原文；关键字统一小写 */
    public final String text;
    public final int pos;

    public Token(Type type, String text, int pos) {
        this.type = type;
        this.text = text;
        this.pos = pos;
    }

    public boolean is(Type t) { return type == t; }

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }

    public static MiniDbException err(String msg, int pos) {
        return new MiniDbException(MiniDbException.Code.PARSE, msg + " (位置 " + pos + ")");
    }
}
