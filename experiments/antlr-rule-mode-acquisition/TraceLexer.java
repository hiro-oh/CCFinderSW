import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;

import java.nio.file.Paths;

public final class TraceLexer {
    private TraceLexer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TraceLexer <lexer-class> <source>");
        }
        Lexer lexer = (Lexer) Class.forName(args[0])
                .getConstructor(org.antlr.v4.runtime.CharStream.class)
                .newInstance(CharStreams.fromPath(Paths.get(args[1])));
        Vocabulary vocabulary = lexer.getVocabulary();
        int index = 0;
        while (true) {
            String before = lexer._mode < lexer.getModeNames().length
                    ? lexer.getModeNames()[lexer._mode] : "<unknown>";
            Token token = lexer.nextToken();
            String after = lexer._mode < lexer.getModeNames().length
                    ? lexer.getModeNames()[lexer._mode] : "<unknown>";
            String name = vocabulary.getSymbolicName(token.getType());
            System.out.printf(
                    "%d\tbefore=%s\tafter=%s\ttype=%d\tname=%s\tchannel=%d\t"
                            + "start=%d\tstop=%d\ttext=%s%n",
                    index++, before, after, token.getType(),
                    name == null ? "<literal>" : name, token.getChannel(),
                    token.getStartIndex(), token.getStopIndex(), escape(token.getText()));
            if (token.getType() == Token.EOF) {
                break;
            }
        }
    }

    private static String escape(String text) {
        return text == null ? "<null>" : text.replace("\\", "\\\\")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
