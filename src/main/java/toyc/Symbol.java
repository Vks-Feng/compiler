package toyc;

enum SymKind { VAR, CONST, FUNC }

final class Symbol {
    final SymKind kind;
    final boolean global;
    final int offset;
    final int constValue;
    final String label;
    final boolean returnsInt;

    private Symbol(SymKind kind, boolean global, int offset, int constValue, String label, boolean returnsInt) {
        this.kind = kind;
        this.global = global;
        this.offset = offset;
        this.constValue = constValue;
        this.label = label;
        this.returnsInt = returnsInt;
    }

    static Symbol var(boolean global, int offset, String label) {
        return new Symbol(SymKind.VAR, global, offset, 0, label, false);
    }

    static Symbol constant(int value) {
        return new Symbol(SymKind.CONST, false, 0, value, null, false);
    }

    static Symbol func(boolean returnsInt) {
        return new Symbol(SymKind.FUNC, true, 0, 0, null, returnsInt);
    }
}
