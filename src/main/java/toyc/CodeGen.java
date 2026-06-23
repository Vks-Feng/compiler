package toyc;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

final class CodeGen {
    private final Program program;
    private final StringBuilder asm = new StringBuilder();
    private final Scope scope = new Scope();
    private final Map<String, Integer> globalInitializers = new LinkedHashMap<>();
    private final Map<String, FuncDef> funcs = new LinkedHashMap<>();
    private int labelId;
    private int localIndex;
    private String endLabel;
    private final ArrayDeque<String> breakLabels = new ArrayDeque<>();
    private final ArrayDeque<String> continueLabels = new ArrayDeque<>();

    CodeGen(Program program) {
        this.program = program;
    }

    String generate() {
        collectGlobalsAndFuncs();
        emitData();
        asm.append(".text\n");
        asm.append(".globl main\n");
        for (FuncDef f : funcs.values()) emitFunc(f);
        return asm.toString();
    }

    private void collectGlobalsAndFuncs() {
        for (Top top : program.tops) {
            if (top instanceof FuncDef f) {
                funcs.put(f.name, f);
                scope.put(f.name, Symbol.func(f.returnsInt));
            } else if (top instanceof Decl d) {
                int value = evalConst(d.init);
                if (d.isConst) {
                    scope.put(d.name, Symbol.constant(value));
                } else {
                    String label = "g_" + d.name;
                    scope.put(d.name, Symbol.var(true, 0, label));
                    globalInitializers.put(label, value);
                }
            }
        }
    }

    private void emitData() {
        if (globalInitializers.isEmpty()) return;
        asm.append(".data\n");
        for (Map.Entry<String, Integer> e : globalInitializers.entrySet()) {
            asm.append(".globl ").append(e.getKey()).append('\n');
            asm.append(e.getKey()).append(":\n");
            asm.append("  .word ").append(e.getValue()).append('\n');
        }
    }

    private void emitFunc(FuncDef f) {
        int slots = f.params.size() + countVars(f.body);
        int frame = align16(8 + slots * 4);
        localIndex = 0;
        endLabel = label(".Lend_" + f.name);
        scope.push();
        asm.append(f.name).append(":\n");
        asm.append("  addi sp, sp, -").append(frame).append('\n');
        asm.append("  sw ra, ").append(frame - 4).append("(sp)\n");
        asm.append("  sw s0, ").append(frame - 8).append("(sp)\n");
        asm.append("  addi s0, sp, ").append(frame).append('\n');
        for (int i = 0; i < f.params.size(); i++) {
            int off = allocSlot();
            scope.put(f.params.get(i), Symbol.var(false, off, null));
            if (i < 8) {
                asm.append("  sw a").append(i).append(", ").append(off).append("(s0)\n");
            } else {
                asm.append("  lw t0, ").append((i - 8) * 4).append("(s0)\n");
                asm.append("  sw t0, ").append(off).append("(s0)\n");
            }
        }
        emitBlock(f.body);
        if (!f.returnsInt) asm.append("  li a0, 0\n");
        asm.append(endLabel).append(":\n");
        asm.append("  lw ra, ").append(frame - 4).append("(sp)\n");
        asm.append("  lw s0, ").append(frame - 8).append("(sp)\n");
        asm.append("  addi sp, sp, ").append(frame).append('\n');
        asm.append("  ret\n");
        scope.pop();
    }

    private int countVars(Stmt stmt) {
        if (stmt instanceof DeclStmt d) return d.decl.isConst ? 0 : 1;
        if (stmt instanceof Block b) {
            int n = 0;
            for (Stmt s : b.stmts) n += countVars(s);
            return n;
        }
        if (stmt instanceof IfStmt i) return countVars(i.thenStmt) + (i.elseStmt == null ? 0 : countVars(i.elseStmt));
        if (stmt instanceof WhileStmt w) return countVars(w.body);
        return 0;
    }

    private int allocSlot() {
        localIndex++;
        return -8 - localIndex * 4;
    }

    private void emitStmt(Stmt stmt) {
        if (stmt instanceof Block b) emitBlock(b);
        else if (stmt instanceof EmptyStmt) {
            // no-op
        } else if (stmt instanceof ExprStmt e) emitExpr(e.expr);
        else if (stmt instanceof AssignStmt a) {
            emitExpr(a.expr);
            storeVar(a.name);
        } else if (stmt instanceof DeclStmt d) emitDecl(d.decl);
        else if (stmt instanceof IfStmt i) emitIf(i);
        else if (stmt instanceof WhileStmt w) emitWhile(w);
        else if (stmt instanceof BreakStmt) asm.append("  j ").append(breakLabels.peek()).append('\n');
        else if (stmt instanceof ContinueStmt) asm.append("  j ").append(continueLabels.peek()).append('\n');
        else if (stmt instanceof ReturnStmt r) {
            if (r.expr != null) emitExpr(r.expr);
            else asm.append("  li a0, 0\n");
            asm.append("  j ").append(endLabel).append('\n');
        }
    }

    private void emitBlock(Block b) {
        scope.push();
        for (Stmt s : b.stmts) emitStmt(s);
        scope.pop();
    }

    private void emitDecl(Decl d) {
        if (d.isConst) {
            scope.put(d.name, Symbol.constant(evalConst(d.init)));
            return;
        }
        int off = allocSlot();
        scope.put(d.name, Symbol.var(false, off, null));
        emitExpr(d.init);
        asm.append("  sw a0, ").append(off).append("(s0)\n");
    }

    private void emitIf(IfStmt i) {
        String elseLabel = label(".Lelse");
        String done = label(".Lendif");
        emitExpr(i.cond);
        asm.append("  beqz a0, ").append(i.elseStmt == null ? done : elseLabel).append('\n');
        emitStmt(i.thenStmt);
        asm.append("  j ").append(done).append('\n');
        if (i.elseStmt != null) {
            asm.append(elseLabel).append(":\n");
            emitStmt(i.elseStmt);
        }
        asm.append(done).append(":\n");
    }

    private void emitWhile(WhileStmt w) {
        String begin = label(".Lwhile");
        String done = label(".Lwend");
        breakLabels.push(done);
        continueLabels.push(begin);
        asm.append(begin).append(":\n");
        emitExpr(w.cond);
        asm.append("  beqz a0, ").append(done).append('\n');
        emitStmt(w.body);
        asm.append("  j ").append(begin).append('\n');
        asm.append(done).append(":\n");
        continueLabels.pop();
        breakLabels.pop();
    }

    private void emitExpr(Expr expr) {
        if (expr instanceof NumExpr n) asm.append("  li a0, ").append(n.value).append('\n');
        else if (expr instanceof VarExpr v) loadVar(v.name);
        else if (expr instanceof UnaryExpr u) emitUnary(u);
        else if (expr instanceof BinaryExpr b) emitBinary(b);
        else if (expr instanceof CallExpr c) emitCall(c);
    }

    private void emitUnary(UnaryExpr u) {
        emitExpr(u.expr);
        switch (u.op) {
            case "-" -> asm.append("  neg a0, a0\n");
            case "!" -> asm.append("  seqz a0, a0\n");
            default -> { }
        }
    }

    private void emitBinary(BinaryExpr b) {
        if (b.op.equals("&&")) {
            String falseLabel = label(".Landfalse");
            String done = label(".Landdone");
            emitExpr(b.left);
            asm.append("  beqz a0, ").append(falseLabel).append('\n');
            emitExpr(b.right);
            asm.append("  beqz a0, ").append(falseLabel).append('\n');
            asm.append("  li a0, 1\n");
            asm.append("  j ").append(done).append('\n');
            asm.append(falseLabel).append(":\n");
            asm.append("  li a0, 0\n");
            asm.append(done).append(":\n");
            return;
        }
        if (b.op.equals("||")) {
            String trueLabel = label(".Lortrue");
            String done = label(".Lordone");
            emitExpr(b.left);
            asm.append("  bnez a0, ").append(trueLabel).append('\n');
            emitExpr(b.right);
            asm.append("  bnez a0, ").append(trueLabel).append('\n');
            asm.append("  li a0, 0\n");
            asm.append("  j ").append(done).append('\n');
            asm.append(trueLabel).append(":\n");
            asm.append("  li a0, 1\n");
            asm.append(done).append(":\n");
            return;
        }
        emitExpr(b.left);
        pushA0();
        emitExpr(b.right);
        popT0();
        switch (b.op) {
            case "+" -> asm.append("  add a0, t0, a0\n");
            case "-" -> asm.append("  sub a0, t0, a0\n");
            case "*" -> asm.append("  mul a0, t0, a0\n");
            case "/" -> asm.append("  div a0, t0, a0\n");
            case "%" -> asm.append("  rem a0, t0, a0\n");
            case "<" -> asm.append("  slt a0, t0, a0\n");
            case ">" -> asm.append("  slt a0, a0, t0\n");
            case "<=" -> asm.append("  slt a0, a0, t0\n  xori a0, a0, 1\n");
            case ">=" -> asm.append("  slt a0, t0, a0\n  xori a0, a0, 1\n");
            case "==" -> asm.append("  sub a0, t0, a0\n  seqz a0, a0\n");
            case "!=" -> asm.append("  sub a0, t0, a0\n  snez a0, a0\n");
            default -> throw new RuntimeException("bad binary operator: " + b.op);
        }
    }

    private void emitCall(CallExpr c) {
        int bytes = align16(c.args.size() * 4);
        if (bytes > 0) asm.append("  addi sp, sp, -").append(bytes).append('\n');
        for (int i = 0; i < c.args.size(); i++) {
            emitExpr(c.args.get(i));
            asm.append("  sw a0, ").append(i * 4).append("(sp)\n");
        }
        for (int i = 0; i < Math.min(8, c.args.size()); i++) {
            asm.append("  lw a").append(i).append(", ").append(i * 4).append("(sp)\n");
        }
        for (int i = 8; i < c.args.size(); i++) {
            asm.append("  lw t0, ").append(i * 4).append("(sp)\n");
            asm.append("  sw t0, ").append((i - 8) * 4).append("(sp)\n");
        }
        asm.append("  call ").append(c.name).append('\n');
        if (bytes > 0) asm.append("  addi sp, sp, ").append(bytes).append('\n');
    }

    private void loadVar(String name) {
        Symbol s = scope.get(name);
        if (s.kind == SymKind.CONST) {
            asm.append("  li a0, ").append(s.constValue).append('\n');
        } else if (s.global) {
            asm.append("  la t0, ").append(s.label).append('\n');
            asm.append("  lw a0, 0(t0)\n");
        } else {
            asm.append("  lw a0, ").append(s.offset).append("(s0)\n");
        }
    }

    private void storeVar(String name) {
        Symbol s = scope.get(name);
        if (s.kind == SymKind.CONST) throw new RuntimeException("cannot assign const: " + name);
        if (s.global) {
            asm.append("  la t0, ").append(s.label).append('\n');
            asm.append("  sw a0, 0(t0)\n");
        } else {
            asm.append("  sw a0, ").append(s.offset).append("(s0)\n");
        }
    }

    private void pushA0() {
        asm.append("  addi sp, sp, -16\n");
        asm.append("  sw a0, 12(sp)\n");
    }

    private void popT0() {
        pop("t0");
    }

    private void pop(String reg) {
        asm.append("  lw ").append(reg).append(", 12(sp)\n");
        asm.append("  addi sp, sp, 16\n");
    }

    private int evalConst(Expr e) {
        if (e instanceof NumExpr n) return n.value;
        if (e instanceof VarExpr v) return scope.get(v.name).constValue;
        if (e instanceof UnaryExpr u) {
            int x = evalConst(u.expr);
            return switch (u.op) {
                case "-" -> -x;
                case "!" -> x == 0 ? 1 : 0;
                default -> x;
            };
        }
        if (e instanceof BinaryExpr b) {
            int l = evalConst(b.left);
            if (b.op.equals("&&")) return l != 0 && evalConst(b.right) != 0 ? 1 : 0;
            if (b.op.equals("||")) return l != 0 || evalConst(b.right) != 0 ? 1 : 0;
            int r = evalConst(b.right);
            return switch (b.op) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> l / r;
                case "%" -> l % r;
                case "<" -> l < r ? 1 : 0;
                case ">" -> l > r ? 1 : 0;
                case "<=" -> l <= r ? 1 : 0;
                case ">=" -> l >= r ? 1 : 0;
                case "==" -> l == r ? 1 : 0;
                case "!=" -> l != r ? 1 : 0;
                default -> throw new RuntimeException("bad const op: " + b.op);
            };
        }
        throw new RuntimeException("not a constant expression");
    }

    private String label(String prefix) {
        return prefix + "_" + (labelId++);
    }

    private int align16(int n) {
        return (n + 15) / 16 * 16;
    }
}
