package toyc;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

final class Scope {
    private final ArrayDeque<Map<String, Symbol>> stack = new ArrayDeque<>();

    Scope() { push(); }
    void push() { stack.push(new HashMap<>()); }
    void pop() { stack.pop(); }
    void put(String name, Symbol sym) { stack.peek().put(name, sym); }

    Symbol get(String name) {
        for (Map<String, Symbol> s : stack) {
            Symbol sym = s.get(name);
            if (sym != null) return sym;
        }
        throw new RuntimeException("undefined symbol: " + name);
    }
}
