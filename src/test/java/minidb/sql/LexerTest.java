package minidb.sql;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 词法分析器：词元、关键字、边界与故障注入 */
class LexerTest {

    private List<Token> lex(String sql) {
        return new Lexer(sql).tokenize();
    }

    @Test
    void keywordsCaseInsensitive() {
        List<Token> ts = lex("select FROM Where");
        assertEquals(Token.Type.SELECT, ts.get(0).type);
        assertEquals(Token.Type.FROM, ts.get(1).type);
        assertEquals(Token.Type.WHERE, ts.get(2).type);
        assertEquals("from", ts.get(1).text); // 关键字统一小写
    }

    @Test
    void identifiersKeepCase() {
        List<Token> ts = lex("MyTable myColumn _x1");
        assertEquals("MyTable", ts.get(0).text);
        assertEquals("myColumn", ts.get(1).text);
        assertEquals("_x1", ts.get(2).text);
    }

    @Test
    void numbersIntAndDouble() {
        List<Token> ts = lex("42 3.14 007 10000000000");
        assertEquals("42", ts.get(0).text);
        assertEquals("3.14", ts.get(1).text);
        assertEquals("007", ts.get(2).text);
    }

    @Test
    void stringLiteralWithEscape() {
        List<Token> ts = lex("'hello' 'it''s' ''");
        assertEquals("hello", ts.get(0).text);
        assertEquals("it's", ts.get(1).text);
        assertEquals("", ts.get(2).text);
    }

    @Test
    void operators() {
        List<Token> ts = lex("= != <> <= >= < > + - * / %");
        assertEquals(List.of(Token.Type.EQ, Token.Type.NEQ, Token.Type.NEQ, Token.Type.LE,
                Token.Type.GE, Token.Type.LT, Token.Type.GT, Token.Type.PLUS, Token.Type.MINUS,
                Token.Type.STAR, Token.Type.SLASH, Token.Type.PERCENT, Token.Type.EOF),
                ts.stream().map(t -> t.type).toList());
    }

    @Test
    void punctuationAndEof() {
        List<Token> ts = lex("( ), . ;");
        assertEquals(List.of(Token.Type.LPAREN, Token.Type.RPAREN, Token.Type.COMMA,
                Token.Type.DOT, Token.Type.SEMI, Token.Type.EOF),
                ts.stream().map(t -> t.type).toList());
    }

    @Test
    void lineCommentIgnored() {
        List<Token> ts = lex("SELECT 1 -- 注释 SELECT 2\n, 3");
        assertEquals(5, ts.size()); // SELECT, 1, ',', 3, EOF
        assertEquals(Token.Type.SELECT, ts.get(0).type);
        assertEquals(Token.Type.EOF, ts.get(4).type);
    }

    @Test
    void unterminatedStringThrows() {
        assertThrows(MiniDbException.class, () -> lex("'no end"));
    }

    @Test
    void illegalCharThrows() {
        assertThrows(MiniDbException.class, () -> lex("SELECT #"));
    }

    @Test
    void loneBangThrows() {
        assertThrows(MiniDbException.class, () -> lex("a ! b"));
    }

    @Test
    void tokenPositionTracked() {
        List<Token> ts = lex("ab cd");
        assertEquals(0, ts.get(0).pos);
        assertEquals(3, ts.get(1).pos);
    }

    @Test
    void fullSqlTokenizes() {
        List<Token> ts = new Lexer(
                "SELECT a.b, COUNT(*) FROM t AS a WHERE x LIKE 'a%' AND y IN (1,2)").tokenize();
        assertEquals(Token.Type.SELECT, ts.get(0).type);
        assertEquals(Token.Type.EOF, ts.get(ts.size() - 1).type);
    }
}
