package toyc;

final class Token {
    final TokenKind kind;
    final String text;
    final int value;

    Token(TokenKind kind, String text, int value) {
        this.kind = kind;
        this.text = text;
        this.value = value;
    }
}
