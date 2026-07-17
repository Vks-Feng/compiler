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
        return peephole(asm.toString());
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
        Integer cond = tryEvalConst(i.cond);
        if (cond != null) {
            if (cond != 0) emitStmt(i.thenStmt);
            else if (i.elseStmt != null) emitStmt(i.elseStmt);
            return;
        }
        String elseLabel = label(".Lelse");
        String done = label(".Lendif");
        emitBranchIfFalse(i.cond, i.elseStmt == null ? done : elseLabel);
        emitStmt(i.thenStmt);
        if (i.elseStmt != null) asm.append("  j ").append(done).append('\n');
        if (i.elseStmt != null) {
            asm.append(elseLabel).append(":\n");
            emitStmt(i.elseStmt);
        }
        asm.append(done).append(":\n");
    }

    private void emitWhile(WhileStmt w) {
        Integer cond = tryEvalConst(w.cond);
        if (cond != null && cond == 0) return;
        String begin = label(".Lwhile");
        String done = label(".Lwend");
        breakLabels.push(done);
        continueLabels.push(begin);
        asm.append(begin).append(":\n");
        emitBranchIfFalse(w.cond, done);
        emitStmt(w.body);
        asm.append("  j ").append(begin).append('\n');
        asm.append(done).append(":\n");
        continueLabels.pop();
        breakLabels.pop();
    }

    private void emitExpr(Expr expr) {
        Integer value = tryEvalConst(expr);
        if (value != null) {
            emitLi("a0", value);
            return;
        }
        if (expr instanceof NumExpr n) emitLi("a0", n.value);
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
        Integer value = tryEvalConst(b);
        if (value != null) {
            emitLi("a0", value);
            return;
        }
        if (b.op.equals("&&")) {
            Integer left = tryEvalConst(b.left);
            if (left != null) {
                if (left == 0) emitLi("a0", 0);
                else {
                    emitExpr(b.right);
                    asm.append("  snez a0, a0\n");
                }
                return;
            }
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
            Integer left = tryEvalConst(b.left);
            if (left != null) {
                if (left != 0) emitLi("a0", 1);
                else {
                    emitExpr(b.right);
                    asm.append("  snez a0, a0\n");
                }
                return;
            }
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
        if (emitAlgebraic(b)) return;
        Integer rightConst = tryEvalConst(b.right);
        if (rightConst != null && emitRightConstBinary(b.op, b.left, rightConst)) return;
        Integer leftConst = tryEvalConst(b.left);
        if (leftConst != null && emitLeftConstBinary(leftConst, b.op, b.right)) return;
        if (isSimpleExpr(b.right)) {
            emitExpr(b.left);
            asm.append("  mv t1, a0\n");
            emitSimpleExprToReg(b.right, "a0");
            emitBinaryOp(b.op, "t1", "a0");
            return;
        }
        emitExpr(b.left);
        pushA0();
        emitExpr(b.right);
        popT0();
        emitBinaryOp(b.op, "t0", "a0");
    }

    private boolean emitAlgebraic(BinaryExpr b) {
        Integer left = tryEvalConst(b.left);
        Integer right = tryEvalConst(b.right);
        if (right != null) {
            switch (b.op) {
                case "+" -> {
                    if (right == 0) {
                        emitExpr(b.left);
                        return true;
                    }
                }
                case "-" -> {
                    if (right == 0) {
                        emitExpr(b.left);
                        return true;
                    }
                }
                case "*" -> {
                    if (right == 0) {
                        emitLi("a0", 0);
                        return true;
                    }
                    if (right == 1) {
                        emitExpr(b.left);
                        return true;
                    }
                    if (right == -1) {
                        emitExpr(b.left);
                        asm.append("  neg a0, a0\n");
                        return true;
                    }
                }
                case "/" -> {
                    if (right == 1) {
                        emitExpr(b.left);
                        return true;
                    }
                }
                case "%" -> {
                    if (right == 1 || right == -1) {
                        emitLi("a0", 0);
                        return true;
                    }
                }
                default -> { }
            }
        }
        if (left != null) {
            switch (b.op) {
                case "+" -> {
                    if (left == 0) {
                        emitExpr(b.right);
                        return true;
                    }
                }
                case "*" -> {
                    if (left == 0) {
                        emitLi("a0", 0);
                        return true;
                    }
                    if (left == 1) {
                        emitExpr(b.right);
                        return true;
                    }
                    if (left == -1) {
                        emitExpr(b.right);
                        asm.append("  neg a0, a0\n");
                        return true;
                    }
                }
                default -> { }
            }
        }
        return false;
    }

    private boolean emitRightConstBinary(String op, Expr left, int value) {
        emitExpr(left);
        switch (op) {
            case "+" -> {
                if (isImm12(value)) asm.append("  addi a0, a0, ").append(value).append('\n');
                else {
                    emitLi("t1", value);
                    asm.append("  add a0, a0, t1\n");
                }
                return true;
            }
            case "-" -> {
                if (isImm12(-value)) asm.append("  addi a0, a0, ").append(-value).append('\n');
                else {
                    emitLi("t1", value);
                    asm.append("  sub a0, a0, t1\n");
                }
                return true;
            }
            case "*" -> {
                int shift = positivePowerOfTwoShift(value);
                if (shift >= 0) asm.append("  slli a0, a0, ").append(shift).append('\n');
                else if (value < 0 && positivePowerOfTwoShift(-value) >= 0) {
                    asm.append("  slli a0, a0, ").append(positivePowerOfTwoShift(-value)).append('\n');
                    asm.append("  neg a0, a0\n");
                } else {
                    emitLi("t1", value);
                    asm.append("  mul a0, a0, t1\n");
                }
                return true;
            }
            case "<" -> {
                if (isImm12(value)) asm.append("  slti a0, a0, ").append(value).append('\n');
                else {
                    emitLi("t1", value);
                    asm.append("  slt a0, a0, t1\n");
                }
                return true;
            }
            case ">" -> {
                if (value != Integer.MAX_VALUE && isImm12(value + 1)) {
                    asm.append("  slti a0, a0, ").append(value + 1).append("\n  xori a0, a0, 1\n");
                } else {
                    emitLi("t1", value);
                    asm.append("  slt a0, t1, a0\n");
                }
                return true;
            }
            case "<=" -> {
                if (value != Integer.MAX_VALUE && isImm12(value + 1)) asm.append("  slti a0, a0, ").append(value + 1).append('\n');
                else {
                    emitLi("t1", value);
                    asm.append("  slt a0, t1, a0\n  xori a0, a0, 1\n");
                }
                return true;
            }
            case ">=" -> {
                if (isImm12(value)) asm.append("  slti a0, a0, ").append(value).append("\n  xori a0, a0, 1\n");
                else {
                    emitLi("t1", value);
                    asm.append("  slt a0, a0, t1\n  xori a0, a0, 1\n");
                }
                return true;
            }
            case "==" -> {
                if (value == 0) asm.append("  seqz a0, a0\n");
                else {
                    emitLi("t1", value);
                    asm.append("  sub a0, a0, t1\n  seqz a0, a0\n");
                }
                return true;
            }
            case "!=" -> {
                if (value == 0) asm.append("  snez a0, a0\n");
                else {
                    emitLi("t1", value);
                    asm.append("  sub a0, a0, t1\n  snez a0, a0\n");
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean emitLeftConstBinary(int value, String op, Expr right) {
        emitExpr(right);
        emitLi("t1", value);
        emitBinaryOp(op, "t1", "a0");
        return true;
    }

    private void emitBinaryOp(String op, String leftReg, String rightReg) {
        switch (op) {
            case "+" -> asm.append("  add a0, ").append(leftReg).append(", ").append(rightReg).append('\n');
            case "-" -> asm.append("  sub a0, ").append(leftReg).append(", ").append(rightReg).append('\n');
            case "*" -> asm.append("  mul a0, ").append(leftReg).append(", ").append(rightReg).append('\n');
            case "/" -> asm.append("  div a0, ").append(leftReg).append(", ").append(rightReg).append('\n');
            case "%" -> asm.append("  rem a0, ").append(leftReg).append(", ").append(rightReg).append('\n');
            case "<" -> asm.append("  slt a0, ").append(leftReg).append(", ").append(rightReg).append('\n');
            case ">" -> asm.append("  slt a0, ").append(rightReg).append(", ").append(leftReg).append('\n');
            case "<=" -> asm.append("  slt a0, ").append(rightReg).append(", ").append(leftReg).append("\n  xori a0, a0, 1\n");
            case ">=" -> asm.append("  slt a0, ").append(leftReg).append(", ").append(rightReg).append("\n  xori a0, a0, 1\n");
            case "==" -> asm.append("  sub a0, ").append(leftReg).append(", ").append(rightReg).append("\n  seqz a0, a0\n");
            case "!=" -> asm.append("  sub a0, ").append(leftReg).append(", ").append(rightReg).append("\n  snez a0, a0\n");
            default -> throw new RuntimeException("bad binary operator: " + op);
        }
    }

    private void emitBranchIfFalse(Expr expr, String target) {
        Integer value = tryEvalConst(expr);
        if (value != null) {
            if (value == 0) asm.append("  j ").append(target).append('\n');
            return;
        }
        if (expr instanceof UnaryExpr u && u.op.equals("!")) {
            emitBranchIfTrue(u.expr, target);
            return;
        }
        if (expr instanceof BinaryExpr b) {
            if (b.op.equals("&&")) {
                emitBranchIfFalse(b.left, target);
                emitBranchIfFalse(b.right, target);
                return;
            }
            if (b.op.equals("||")) {
                String done = label(".Lorbranchdone");
                emitBranchIfTrue(b.left, done);
                emitBranchIfFalse(b.right, target);
                asm.append(done).append(":\n");
                return;
            }
            if (isRelOp(b.op)) {
                emitRelBranch(b.left, b.op, b.right, false, target);
                return;
            }
        }
        emitExpr(expr);
        asm.append("  beqz a0, ").append(target).append('\n');
    }

    private void emitBranchIfTrue(Expr expr, String target) {
        Integer value = tryEvalConst(expr);
        if (value != null) {
            if (value != 0) asm.append("  j ").append(target).append('\n');
            return;
        }
        if (expr instanceof UnaryExpr u && u.op.equals("!")) {
            emitBranchIfFalse(u.expr, target);
            return;
        }
        if (expr instanceof BinaryExpr b) {
            if (b.op.equals("&&")) {
                String done = label(".Landbranchdone");
                emitBranchIfFalse(b.left, done);
                emitBranchIfTrue(b.right, target);
                asm.append(done).append(":\n");
                return;
            }
            if (b.op.equals("||")) {
                emitBranchIfTrue(b.left, target);
                emitBranchIfTrue(b.right, target);
                return;
            }
            if (isRelOp(b.op)) {
                emitRelBranch(b.left, b.op, b.right, true, target);
                return;
            }
        }
        emitExpr(expr);
        asm.append("  bnez a0, ").append(target).append('\n');
    }

    private void emitRelBranch(Expr left, String op, Expr right, boolean branchOnTrue, String target) {
        Integer rightConst = tryEvalConst(right);
        if (rightConst != null) {
            emitExpr(left);
            emitLi("t1", rightConst);
            emitBranchOp(op, "a0", "t1", branchOnTrue, target);
            return;
        }
        Integer leftConst = tryEvalConst(left);
        if (leftConst != null) {
            emitExpr(right);
            emitLi("t1", leftConst);
            emitBranchOp(op, "t1", "a0", branchOnTrue, target);
            return;
        }
        if (isSimpleExpr(right)) {
            emitExpr(left);
            asm.append("  mv t1, a0\n");
            emitSimpleExprToReg(right, "a0");
            emitBranchOp(op, "t1", "a0", branchOnTrue, target);
            return;
        }
        emitExpr(left);
        pushA0();
        emitExpr(right);
        popT0();
        emitBranchOp(op, "t0", "a0", branchOnTrue, target);
    }

    private void emitBranchOp(String op, String leftReg, String rightReg, boolean branchOnTrue, String target) {
        String branch = switch (op) {
            case "<" -> branchOnTrue ? "blt" : "bge";
            case ">" -> branchOnTrue ? "blt" : "bge";
            case "<=" -> branchOnTrue ? "bge" : "blt";
            case ">=" -> branchOnTrue ? "bge" : "blt";
            case "==" -> branchOnTrue ? "beq" : "bne";
            case "!=" -> branchOnTrue ? "bne" : "beq";
            default -> throw new RuntimeException("bad branch operator: " + op);
        };
        boolean swapped = op.equals(">") || op.equals("<=");
        String a = swapped ? rightReg : leftReg;
        String b = swapped ? leftReg : rightReg;
        asm.append("  ").append(branch).append(' ').append(a).append(", ").append(b).append(", ").append(target).append('\n');
    }

    private void emitCall(CallExpr c) {
        if (c.args.size() <= 8 && c.args.stream().allMatch(this::isSimpleExpr)) {
            for (int i = 0; i < c.args.size(); i++) {
                emitSimpleExprToReg(c.args.get(i), "a" + i);
            }
            asm.append("  call ").append(c.name).append('\n');
            return;
        }
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
        loadVarToReg(name, "a0");
    }

    private void loadVarToReg(String name, String reg) {
        Symbol s = scope.get(name);
        if (s.kind == SymKind.CONST) {
            emitLi(reg, s.constValue);
        } else if (s.global) {
            asm.append("  la t0, ").append(s.label).append('\n');
            asm.append("  lw ").append(reg).append(", 0(t0)\n");
        } else {
            asm.append("  lw ").append(reg).append(", ").append(s.offset).append("(s0)\n");
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

    private void emitSimpleExprToReg(Expr expr, String reg) {
        Integer value = tryEvalConst(expr);
        if (value != null) {
            emitLi(reg, value);
        } else if (expr instanceof VarExpr v) {
            loadVarToReg(v.name, reg);
        } else {
            throw new RuntimeException("not a simple expression");
        }
    }

    private boolean isSimpleExpr(Expr expr) {
        return tryEvalConst(expr) != null || expr instanceof VarExpr;
    }

    private Integer tryEvalConst(Expr e) {
        try {
            return evalConst(e);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isRelOp(String op) {
        return op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=") || op.equals("==") || op.equals("!=");
    }

    private boolean isImm12(int value) {
        return value >= -2048 && value <= 2047;
    }

    private int positivePowerOfTwoShift(int value) {
        if (value <= 0 || (value & (value - 1)) != 0) return -1;
        return Integer.numberOfTrailingZeros(value);
    }

    private void emitLi(String reg, int value) {
        asm.append("  li ").append(reg).append(", ").append(value).append('\n');
    }

    private String peephole(String code) {
        String[] lines = code.split("\n", -1);
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < lines.length; i++) {
            if (i + 1 < lines.length && lines[i].startsWith("  j ")) {
                String target = lines[i].substring(4);
                if (lines[i + 1].equals(target + ":")) continue;
            }
            out.append(lines[i]);
            if (i + 1 < lines.length) out.append('\n');
        }
        return out.toString();
    }

    private int evalConst(Expr e) {
        if (e instanceof NumExpr n) return n.value;
        if (e instanceof VarExpr v) {
            Symbol sym = scope.get(v.name);
            if (sym.kind != SymKind.CONST) throw new RuntimeException("not a constant: " + v.name);
            return sym.constValue;
        }
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
