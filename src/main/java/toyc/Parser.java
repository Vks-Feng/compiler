package toyc;

import java.util.ArrayList;
import java.util.List;

final class Parser {
    private final List<Token> ts;
    private int p;

    Parser(List<Token> ts) {
        this.ts = ts;
    }

    Program parseProgram() {
        List<Top> tops = new ArrayList<>();
        while (!at(TokenKind.EOF)) {
            if (at(TokenKind.CONST)) {
                tops.add(parseDecl());
            } else if (at(TokenKind.INT) || at(TokenKind.VOID)) {
                if (isFuncDef()) tops.add(parseFuncDef());
                else tops.add(parseDecl());
            } else {
                throw error("expected declaration or function definition");
            }
        }
        return new Program(tops);
    }

    private boolean isFuncDef() {
        int q = p;
        if (ts.get(q).kind == TokenKind.INT || ts.get(q).kind == TokenKind.VOID) q++;
        else return false;
        return ts.get(q).kind == TokenKind.ID && ts.get(q + 1).kind == TokenKind.LPAREN;
    }

    private Decl parseDecl() {
        boolean isConst = accept(TokenKind.CONST);
        expect(TokenKind.INT);
        String name = expect(TokenKind.ID).text;
        expect(TokenKind.ASSIGN);
        Expr init = parseExpr();
        expect(TokenKind.SEMI);
        return new Decl(isConst, name, init);
    }

    private FuncDef parseFuncDef() {
        boolean returnsInt;
        if (accept(TokenKind.INT)) returnsInt = true;
        else {
            expect(TokenKind.VOID);
            returnsInt = false;
        }
        String name = expect(TokenKind.ID).text;
        expect(TokenKind.LPAREN);
        List<String> params = new ArrayList<>();
        if (!at(TokenKind.RPAREN)) {
            do {
                expect(TokenKind.INT);
                params.add(expect(TokenKind.ID).text);
            } while (accept(TokenKind.COMMA));
        }
        expect(TokenKind.RPAREN);
        return new FuncDef(returnsInt, name, params, parseBlock());
    }

    private Block parseBlock() {
        expect(TokenKind.LBRACE);
        List<Stmt> stmts = new ArrayList<>();
        while (!at(TokenKind.RBRACE)) stmts.add(parseStmt());
        expect(TokenKind.RBRACE);
        return new Block(stmts);
    }

    private Stmt parseStmt() {
        if (at(TokenKind.LBRACE)) return parseBlock();
        if (accept(TokenKind.SEMI)) return new EmptyStmt();
        if (at(TokenKind.CONST) || (at(TokenKind.INT) && !isFuncDef())) return new DeclStmt(parseDecl());
        if (accept(TokenKind.IF)) {
            expect(TokenKind.LPAREN);
            Expr cond = parseExpr();
            expect(TokenKind.RPAREN);
            Stmt thenStmt = parseStmt();
            Stmt elseStmt = accept(TokenKind.ELSE) ? parseStmt() : null;
            return new IfStmt(cond, thenStmt, elseStmt);
        }
        if (accept(TokenKind.WHILE)) {
            expect(TokenKind.LPAREN);
            Expr cond = parseExpr();
            expect(TokenKind.RPAREN);
            return new WhileStmt(cond, parseStmt());
        }
        if (accept(TokenKind.BREAK)) {
            expect(TokenKind.SEMI);
            return new BreakStmt();
        }
        if (accept(TokenKind.CONTINUE)) {
            expect(TokenKind.SEMI);
            return new ContinueStmt();
        }
        if (accept(TokenKind.RETURN)) {
            Expr expr = at(TokenKind.SEMI) ? null : parseExpr();
            expect(TokenKind.SEMI);
            return new ReturnStmt(expr);
        }
        if (at(TokenKind.ID) && ts.get(p + 1).kind == TokenKind.ASSIGN) {
            String name = expect(TokenKind.ID).text;
            expect(TokenKind.ASSIGN);
            Expr expr = parseExpr();
            expect(TokenKind.SEMI);
            return new AssignStmt(name, expr);
        }
        Expr expr = parseExpr();
        expect(TokenKind.SEMI);
        return new ExprStmt(expr);
    }

    private Expr parseExpr() { return parseOr(); }

    private Expr parseOr() {
        Expr e = parseAnd();
        while (accept(TokenKind.OROR)) e = new BinaryExpr("||", e, parseAnd());
        return e;
    }

    private Expr parseAnd() {
        Expr e = parseRel();
        while (accept(TokenKind.ANDAND)) e = new BinaryExpr("&&", e, parseRel());
        return e;
    }

    private Expr parseRel() {
        Expr e = parseAdd();
        while (true) {
            if (accept(TokenKind.LT)) e = new BinaryExpr("<", e, parseAdd());
            else if (accept(TokenKind.GT)) e = new BinaryExpr(">", e, parseAdd());
            else if (accept(TokenKind.LE)) e = new BinaryExpr("<=", e, parseAdd());
            else if (accept(TokenKind.GE)) e = new BinaryExpr(">=", e, parseAdd());
            else if (accept(TokenKind.EQEQ)) e = new BinaryExpr("==", e, parseAdd());
            else if (accept(TokenKind.NEQ)) e = new BinaryExpr("!=", e, parseAdd());
            else return e;
        }
    }

    private Expr parseAdd() {
        Expr e = parseMul();
        while (true) {
            if (accept(TokenKind.PLUS)) e = new BinaryExpr("+", e, parseMul());
            else if (accept(TokenKind.MINUS)) e = new BinaryExpr("-", e, parseMul());
            else return e;
        }
    }

    private Expr parseMul() {
        Expr e = parseUnary();
        while (true) {
            if (accept(TokenKind.STAR)) e = new BinaryExpr("*", e, parseUnary());
            else if (accept(TokenKind.SLASH)) e = new BinaryExpr("/", e, parseUnary());
            else if (accept(TokenKind.PERCENT)) e = new BinaryExpr("%", e, parseUnary());
            else return e;
        }
    }

    private Expr parseUnary() {
        if (accept(TokenKind.PLUS)) return new UnaryExpr("+", parseUnary());
        if (accept(TokenKind.MINUS)) return new UnaryExpr("-", parseUnary());
        if (accept(TokenKind.BANG)) return new UnaryExpr("!", parseUnary());
        return parsePrimary();
    }

    private Expr parsePrimary() {
        if (accept(TokenKind.LPAREN)) {
            Expr e = parseExpr();
            expect(TokenKind.RPAREN);
            return e;
        }
        if (at(TokenKind.NUMBER)) return new NumExpr(expect(TokenKind.NUMBER).value);
        String name = expect(TokenKind.ID).text;
        if (accept(TokenKind.LPAREN)) {
            List<Expr> args = new ArrayList<>();
            if (!at(TokenKind.RPAREN)) {
                do args.add(parseExpr());
                while (accept(TokenKind.COMMA));
            }
            expect(TokenKind.RPAREN);
            return new CallExpr(name, args);
        }
        return new VarExpr(name);
    }

    private boolean at(TokenKind kind) {
        return ts.get(p).kind == kind;
    }

    private boolean accept(TokenKind kind) {
        if (at(kind)) {
            p++;
            return true;
        }
        return false;
    }

    private Token expect(TokenKind kind) {
        Token t = ts.get(p);
        if (t.kind != kind) throw error("expected " + kind + " but got " + t.kind);
        p++;
        return t;
    }

    private RuntimeException error(String msg) {
        Token t = ts.get(Math.min(p, ts.size() - 1));
        return new RuntimeException("parser error near '" + t.text + "': " + msg);
    }
}
