package toyc;

import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        Program program = new Parser(new Lexer(source).lex()).parseProgram();
        System.out.print(new CodeGen(program).generate());
    }
}
