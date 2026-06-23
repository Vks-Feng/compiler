package toyc;

import java.util.ArrayList;
import java.util.List;

final class Lexer {
    private final String s;
    private int p;
    private final List<Token> out = new ArrayList<>();

    Lexer(String s) {
        this.s = s;
    }

    List<Token> lex() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c)) {
                p++;
            } else if (c == '/' && peek(1) == '/') {
                p += 2;
                while (p < s.length() && s.charAt(p) != '\n') p++;
            } else if (c == '/' && peek(1) == '*') {
                p += 2;
                while (p + 1 < s.length() && !(s.charAt(p) == '*' && s.charAt(p + 1) == '/')) p++;
                p += 2;
            } else if (Character.isDigit(c)) {
                number();
            } else if (c == '_' || Character.isLetter(c)) {
                ident();
            } else {
                punct();
            }
        }
        out.add(new Token(TokenKind.EOF, "", 0));
        return out;
    }

    private char peek(int d) {
        int i = p + d;
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void number() {
        int start = p;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        String text = s.substring(start, p);
        out.add(new Token(TokenKind.NUMBER, text, (int) Long.parseLong(text)));
    }

    private void ident() {
        int start = p++;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '_' || Character.isLetterOrDigit(c)) p++;
            else break;
        }
        String text = s.substring(start, p);
        TokenKind kind = switch (text) {
            case "const" -> TokenKind.CONST;
            case "int" -> TokenKind.INT;
            case "void" -> TokenKind.VOID;
            case "if" -> TokenKind.IF;
            case "else" -> TokenKind.ELSE;
            case "while" -> TokenKind.WHILE;
            case "break" -> TokenKind.BREAK;
            case "continue" -> TokenKind.CONTINUE;
            case "return" -> TokenKind.RETURN;
            default -> TokenKind.ID;
        };
        out.add(new Token(kind, text, 0));
    }

    private void punct() {
        char c = s.charAt(p++);
        switch (c) {
            case '+' -> add(TokenKind.PLUS, "+");
            case '-' -> add(TokenKind.MINUS, "-");
            case '*' -> add(TokenKind.STAR, "*");
            case '/' -> add(TokenKind.SLASH, "/");
            case '%' -> add(TokenKind.PERCENT, "%");
            case ';' -> add(TokenKind.SEMI, ";");
            case ',' -> add(TokenKind.COMMA, ",");
            case '(' -> add(TokenKind.LPAREN, "(");
            case ')' -> add(TokenKind.RPAREN, ")");
            case '{' -> add(TokenKind.LBRACE, "{");
            case '}' -> add(TokenKind.RBRACE, "}");
            case '!' -> {
                if (match('=')) add(TokenKind.NEQ, "!=");
                else add(TokenKind.BANG, "!");
            }
            case '=' -> {
                if (match('=')) add(TokenKind.EQEQ, "==");
                else add(TokenKind.ASSIGN, "=");
            }
            case '<' -> {
                if (match('=')) add(TokenKind.LE, "<=");
                else add(TokenKind.LT, "<");
            }
            case '>' -> {
                if (match('=')) add(TokenKind.GE, ">=");
                else add(TokenKind.GT, ">");
            }
            case '&' -> {
                if (match('&')) add(TokenKind.ANDAND, "&&");
                else throw error("unexpected '&'");
            }
            case '|' -> {
                if (match('|')) add(TokenKind.OROR, "||");
                else throw error("unexpected '|'");
            }
            default -> throw error("unexpected character: " + c);
        }
    }

    private boolean match(char c) {
        if (p < s.length() && s.charAt(p) == c) {
            p++;
            return true;
        }
        return false;
    }

    private void add(TokenKind kind, String text) {
        out.add(new Token(kind, text, 0));
    }

    private RuntimeException error(String msg) {
        return new RuntimeException("lexer error at " + p + ": " + msg);
    }
}
