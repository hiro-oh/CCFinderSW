import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;

import java.nio.file.Paths;

public final class TokenDump {
    private TokenDump() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TokenDump <lexer-class> <source>");
        }
        Lexer lexer = (Lexer) Class.forName(args[0])
                .getConstructor(org.antlr.v4.runtime.CharStream.class)
                .newInstance(CharStreams.fromPath(Paths.get(args[1])));
        Vocabulary vocabulary = lexer.getVocabulary();
        int tokenIndex = 0;
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            String symbolicName = vocabulary.getSymbolicName(token.getType());
            System.out.printf("%d\t%d\t%s\tchannel=%d\tline=%d\tcol=%d\tstart=%d\tstop=%d\t%s%n",
                    tokenIndex++, token.getType(), symbolicName == null ? "<literal>" : symbolicName,
                    token.getChannel(), token.getLine(), token.getCharPositionInLine(),
                    token.getStartIndex(), token.getStopIndex(), escape(token.getText()));
        }
    }

    private static String escape(String text) {
        return text == null ? "<null>" : text.replace("\\", "\\\\")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}