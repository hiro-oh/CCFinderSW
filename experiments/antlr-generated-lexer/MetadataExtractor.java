import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.RuleStartState;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MetadataExtractor {
    private MetadataExtractor() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: MetadataExtractor <language> <lexer-class> <grammar-file> <output>");
        }
        String language = args[0];
        String lexerClass = args[1];
        String grammarFile = args[2];
        Path output = Paths.get(args[3]);

        Class<?> type = Class.forName(lexerClass);
        Constructor<?> constructor = type.getConstructor(org.antlr.v4.runtime.CharStream.class);
        Lexer lexer = (Lexer) constructor.newInstance(CharStreams.fromString(""));
        Vocabulary vocabulary = lexer.getVocabulary();
        ATN atn = lexer.getATN();
        String[] ruleNames = lexer.getRuleNames();
        String[] modeNames = lexer.getModeNames();
        String[] channelNames = lexer.getChannelNames();
        Map<Integer, String> ruleByToken = new HashMap<>();
        for (int rule = 0; rule < atn.ruleToTokenType.length; rule++) {
            int tokenType = atn.ruleToTokenType[rule];
            if (tokenType >= 0 && rule < ruleNames.length) {
                ruleByToken.put(tokenType, ruleNames[rule]);
            }
        }
        Map<Integer, String> modeByRule = new HashMap<>();
        for (int mode = 0; mode < atn.modeToStartState.size(); mode++) {
            ATNState start = atn.modeToStartState.get(mode);
            for (int transition = 0; transition < start.getNumberOfTransitions(); transition++) {
                if (start.transition(transition) instanceof RuleTransition) {
                    RuleTransition ruleTransition = (RuleTransition) start.transition(transition);
                    modeByRule.put(ruleTransition.ruleIndex, mode < modeNames.length ? modeNames[mode] : null);
                } else if (start.transition(transition).target instanceof RuleStartState) {
                    RuleStartState ruleStart = (RuleStartState) start.transition(transition).target;
                    modeByRule.put(ruleStart.ruleIndex, mode < modeNames.length ? modeNames[mode] : null);
                }
            }
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("language", language);
        document.put("lexerClass", lexerClass);
        document.put("grammarFile", grammarFile);
        document.put("tokenMaxType", vocabulary.getMaxTokenType());
        document.put("ruleNames", Arrays.asList(ruleNames));
        document.put("modeNames", Arrays.asList(modeNames));
        document.put("channelNames", Arrays.asList(channelNames));
        Map<String, Object> atnInfo = new LinkedHashMap<>();
        atnInfo.put("ruleCount", atn.ruleToStartState.length);
        atnInfo.put("modeCount", atn.modeToStartState.size());
        atnInfo.put("stateCount", atn.states.size());
        document.put("atn", atnInfo);
        document.put("tokenRuleMapping", "ATN.ruleToTokenType; implicit literal tokens may have no lexer rule");

        List<Map<String, Object>> tokens = new ArrayList<>();
        for (int tokenType = 0; tokenType <= vocabulary.getMaxTokenType(); tokenType++) {
            String symbolic = vocabulary.getSymbolicName(tokenType);
            String literal = vocabulary.getLiteralName(tokenType);
            String display = vocabulary.getDisplayName(tokenType);
            if (symbolic == null && literal == null && display == null) {
                continue;
            }
            Map<String, Object> token = new LinkedHashMap<>();
            token.put("tokenType", tokenType);
            token.put("symbolicName", symbolic);
            token.put("literalName", literal);
            token.put("displayName", display);
            token.put("ruleName", ruleByToken.get(tokenType));
            token.put("mode", modeForRule(ruleByToken.get(tokenType), ruleNames, modeByRule, modeNames));
            token.put("mappingStatus", ruleByToken.containsKey(tokenType) ? "ATN_RULE_MAPPING" : "NO_LEXER_RULE_MAPPING");
            tokens.add(token);
        }
        document.put("tokens", tokens);
        List<String> limitations = new ArrayList<>();
        limitations.add("Action and semantic predicate source is not reconstructed from the ATN");
        limitations.add("skip/more effects and contextual keyword meaning require lexer execution or grammar-source inspection");
        limitations.add("Implicit literal token types may not have a lexer rule name");
        document.put("limitations", limitations);
        Files.createDirectories(output.getParent());
        Files.write(output, json(document).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String modeForRule(String ruleName, String[] ruleNames, Map<Integer, String> modeByRule, String[] modeNames) {
        if (ruleName == null) {
            return null;
        }
        for (int index = 0; index < ruleNames.length; index++) {
            if (ruleName.equals(ruleNames[index])) {
                return modeByRule.get(index);
            }
        }
        return modeNames.length == 0 ? null : modeNames[0];
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof List) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) result.append(',');
                result.append(json(item));
                first = false;
            }
            return result.append(']').toString();
        }
        if (value instanceof Map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) result.append(',');
                result.append(quote(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
                first = false;
            }
            return result.append('}').toString();
        }
        throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
            }
        }
        return result.append('"').toString();
    }
}