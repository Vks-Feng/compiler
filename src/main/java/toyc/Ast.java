package toyc;

import java.util.List;

sealed interface Top permits Decl, FuncDef {}
sealed interface Stmt permits Block, EmptyStmt, ExprStmt, AssignStmt, DeclStmt, IfStmt, WhileStmt, BreakStmt, ContinueStmt, ReturnStmt {}
sealed interface Expr permits NumExpr, VarExpr, CallExpr, UnaryExpr, BinaryExpr {}

final class Program {
    final List<Top> tops;
    Program(List<Top> tops) { this.tops = tops; }
}

final class Decl implements Top {
    final boolean isConst;
    final String name;
    final Expr init;
    Decl(boolean isConst, String name, Expr init) {
        this.isConst = isConst;
        this.name = name;
        this.init = init;
    }
}

final class FuncDef implements Top {
    final boolean returnsInt;
    final String name;
    final List<String> params;
    final Block body;
    FuncDef(boolean returnsInt, String name, List<String> params, Block body) {
        this.returnsInt = returnsInt;
        this.name = name;
        this.params = params;
        this.body = body;
    }
}

final class Block implements Stmt {
    final List<Stmt> stmts;
    Block(List<Stmt> stmts) { this.stmts = stmts; }
}

final class EmptyStmt implements Stmt {}

final class ExprStmt implements Stmt {
    final Expr expr;
    ExprStmt(Expr expr) { this.expr = expr; }
}

final class AssignStmt implements Stmt {
    final String name;
    final Expr expr;
    AssignStmt(String name, Expr expr) { this.name = name; this.expr = expr; }
}

final class DeclStmt implements Stmt {
    final Decl decl;
    DeclStmt(Decl decl) { this.decl = decl; }
}

final class IfStmt implements Stmt {
    final Expr cond;
    final Stmt thenStmt;
    final Stmt elseStmt;
    IfStmt(Expr cond, Stmt thenStmt, Stmt elseStmt) {
        this.cond = cond;
        this.thenStmt = thenStmt;
        this.elseStmt = elseStmt;
    }
}

final class WhileStmt implements Stmt {
    final Expr cond;
    final Stmt body;
    WhileStmt(Expr cond, Stmt body) { this.cond = cond; this.body = body; }
}

final class BreakStmt implements Stmt {}
final class ContinueStmt implements Stmt {}

final class ReturnStmt implements Stmt {
    final Expr expr;
    ReturnStmt(Expr expr) { this.expr = expr; }
}

final class NumExpr implements Expr {
    final int value;
    NumExpr(int value) { this.value = value; }
}

final class VarExpr implements Expr {
    final String name;
    VarExpr(String name) { this.name = name; }
}

final class CallExpr implements Expr {
    final String name;
    final List<Expr> args;
    CallExpr(String name, List<Expr> args) { this.name = name; this.args = args; }
}

final class UnaryExpr implements Expr {
    final String op;
    final Expr expr;
    UnaryExpr(String op, Expr expr) { this.op = op; this.expr = expr; }
}

final class BinaryExpr implements Expr {
    final String op;
    final Expr left;
    final Expr right;
    BinaryExpr(String op, Expr left, Expr right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }
}
