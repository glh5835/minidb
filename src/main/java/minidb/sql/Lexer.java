package minidb.sql;

import minidb.common.MiniDbException;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写词法分析器：标识符（含关键字识别，大小写不敏感）、数字（整数/小数）、
 * 单引号字符串（'' 转义）、运算符与标点。支持 -- 行注释。
 */
public final class Lexer {
    private final String src;
    private int pos;

    public Lexer(String src) {
        this.src = src;
    }

    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWs();
            if (pos >= src.length()) {
                out.add(new Token(Token.Type.EOF, "", pos));
                return out;
            }
            out.add(next());
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) pos++;
            else if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else break;
        }
    }

    private Token next() {
        int start = pos;
        char c = src.charAt(pos);
        if (Character.isLetter(c) || c == '_') return ident();
        if (Character.isDigit(c)) return number();
        switch (c) {
            case '\'': return string();
            case '=': pos++; return new Token(Token.Type.EQ, "=", start);
            case '!':
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
                    pos += 2;
                    return new Token(Token.Type.NEQ, "!=", start);
                }
                throw Token.err("孤立的 '!'", start);
            case '<':
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
                    pos += 2;
                    return new Token(Token.Type.LE, "<=", start);
                }
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '>') {
                    pos += 2;
                    return new Token(Token.Type.NEQ, "<>", start);
                }
                pos++;
                return new Token(Token.Type.LT, "<", start);
            case '>':
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
                    pos += 2;
                    return new Token(Token.Type.GE, ">=", start);
                }
                pos++;
                return new Token(Token.Type.GT, ">", start);
            case '+': pos++; return new Token(Token.Type.PLUS, "+", start);
            case '-': pos++; return new Token(Token.Type.MINUS, "-", start);
            case '*': pos++; return new Token(Token.Type.STAR, "*", start);
            case '/': pos++; return new Token(Token.Type.SLASH, "/", start);
            case '%': pos++; return new Token(Token.Type.PERCENT, "%", start);
            case '(': pos++; return new Token(Token.Type.LPAREN, "(", start);
            case ')': pos++; return new Token(Token.Type.RPAREN, ")", start);
            case ',': pos++; return new Token(Token.Type.COMMA, ",", start);
            case '.': pos++; return new Token(Token.Type.DOT, ".", start);
            case ';': pos++; return new Token(Token.Type.SEMI, ";", start);
            default: throw Token.err("非法字符 '" + c + "'", start);
        }
    }

    private static final java.util.Map<String, Token.Type> KEYWORDS = java.util.Map.ofEntries(
            java.util.Map.entry("select", Token.Type.SELECT),
            java.util.Map.entry("from", Token.Type.FROM),
            java.util.Map.entry("where", Token.Type.WHERE),
            java.util.Map.entry("and", Token.Type.AND),
            java.util.Map.entry("or", Token.Type.OR),
            java.util.Map.entry("not", Token.Type.NOT),
            java.util.Map.entry("like", Token.Type.LIKE),
            java.util.Map.entry("in", Token.Type.IN),
            java.util.Map.entry("is", Token.Type.IS),
            java.util.Map.entry("null", Token.Type.NULL),
            java.util.Map.entry("join", Token.Type.JOIN),
            java.util.Map.entry("inner", Token.Type.INNER),
            java.util.Map.entry("left", Token.Type.LEFT),
            java.util.Map.entry("outer", Token.Type.OUTER),
            java.util.Map.entry("on", Token.Type.ON),
            java.util.Map.entry("as", Token.Type.AS),
            java.util.Map.entry("order", Token.Type.ORDER),
            java.util.Map.entry("group", Token.Type.GROUP),
            java.util.Map.entry("by", Token.Type.BY),
            java.util.Map.entry("having", Token.Type.HAVING),
            java.util.Map.entry("limit", Token.Type.LIMIT),
            java.util.Map.entry("offset", Token.Type.OFFSET),
            java.util.Map.entry("distinct", Token.Type.DISTINCT),
            java.util.Map.entry("insert", Token.Type.INSERT),
            java.util.Map.entry("into", Token.Type.INTO),
            java.util.Map.entry("values", Token.Type.VALUES),
            java.util.Map.entry("update", Token.Type.UPDATE),
            java.util.Map.entry("set", Token.Type.SET),
            java.util.Map.entry("delete", Token.Type.DELETE),
            java.util.Map.entry("create", Token.Type.CREATE),
            java.util.Map.entry("table", Token.Type.TABLE),
            java.util.Map.entry("drop", Token.Type.DROP),
            java.util.Map.entry("index", Token.Type.INDEX),
            java.util.Map.entry("primary", Token.Type.PRIMARY),
            java.util.Map.entry("key", Token.Type.KEY),
            java.util.Map.entry("exists", Token.Type.EXISTS),
            java.util.Map.entry("asc", Token.Type.ASC),
            java.util.Map.entry("desc", Token.Type.DESC),
            java.util.Map.entry("between", Token.Type.BETWEEN),
            java.util.Map.entry("count", Token.Type.COUNT),
            java.util.Map.entry("sum", Token.Type.SUM),
            java.util.Map.entry("avg", Token.Type.AVG),
            java.util.Map.entry("min", Token.Type.MIN),
            java.util.Map.entry("max", Token.Type.MAX),
            java.util.Map.entry("int", Token.Type.INT),
            java.util.Map.entry("integer", Token.Type.INTEGER),
            java.util.Map.entry("bigint", Token.Type.BIGINT),
            java.util.Map.entry("long", Token.Type.LONG),
            java.util.Map.entry("double", Token.Type.DOUBLE),
            java.util.Map.entry("float", Token.Type.FLOAT),
            java.util.Map.entry("varchar", Token.Type.VARCHAR),
            java.util.Map.entry("string", Token.Type.STRING_TYPE),
            java.util.Map.entry("begin", Token.Type.BEGIN),
            java.util.Map.entry("commit", Token.Type.COMMIT),
            java.util.Map.entry("rollback", Token.Type.ROLLBACK),
            java.util.Map.entry("if", Token.Type.IF));

    private Token ident() {
        int start = pos;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            sb.append(src.charAt(pos));
            pos++;
        }
        String word = sb.toString();
        Token.Type kw = KEYWORDS.get(word.toLowerCase());
        if (kw != null) return new Token(kw, word.toLowerCase(), start);
        return new Token(Token.Type.IDENT, word, start);
    }

    private Token number() {
        int start = pos;
        boolean dot = false;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            if (src.charAt(pos) == '.') {
                if (dot) throw Token.err("数字里有两个小数点", start);
                dot = true;
            }
            pos++;
        }
        return new Token(Token.Type.NUMBER, src.substring(start, pos), start);
    }

    private Token string() {
        int start = pos;
        pos++; // 跳过开头引号
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\'') {
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '\'') { // '' 转义
                    sb.append('\'');
                    pos += 2;
                    continue;
                }
                pos++;
                return new Token(Token.Type.STRING, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw Token.err("字符串缺少结束引号", start);
    }
}
