# ToyC Compiler

武汉大学软件工程 2026 年暑期实践《编译原理实践》ToyC 编译器实现。

## Build

```bash
mvn -q -DskipTests package
```

## Run

编译器从标准输入读取 ToyC 源码，向标准输出写出 RISC-V32 汇编。

```bash
java -jar target/toyc-compiler-1.0.0.jar < input.tc > output.s
```

`-opt` 参数当前会被忽略，默认生成语义正确的汇编代码。

## Structure

- `Main.java`: 命令行入口，负责标准输入输出。
- `Lexer.java`, `Token.java`, `TokenKind.java`: 词法分析。
- `Parser.java`: 递归下降语法分析。
- `Ast.java`: AST 节点定义。
- `Scope.java`, `Symbol.java`: 符号与作用域。
- `CodeGen.java`: RISC-V32 汇编生成。
