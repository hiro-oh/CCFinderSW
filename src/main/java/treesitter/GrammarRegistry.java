package treesitter;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * tree-sitter-config.json をロードし，language key → Grammar entry を管理．
 * エントリには grammar ディレクトリ，query ファイル，tree-sitter 言語名を含む．
 */
public class GrammarRegistry {
    public static class Entry {
        public String grammar;          // 例: "treesitter-grammars/tree-sitter-java"
        public String query;            // 例: "treesitter-grammars/tree-sitter-java/queries/highlights.scm"
        public String tsLanguageName;   // 例: "java"

        // Jackson 用デフォルトコンストラクタ
        public Entry() {}

        public Entry(String grammar, String query, String tsLanguageName) {
            this.grammar = grammar;
            this.query = query;
            this.tsLanguageName = tsLanguageName;
        }
    }

    private final Map<String, Entry> entries;

    private GrammarRegistry(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /**
     * treesitter-config.json をロード．
     * @param configFile treesitter-config.json のパス
     * @return GrammarRegistry インスタンス
     */
    public static GrammarRegistry load(Path configFile) throws Exception {
        Map<String, Entry> m = new ObjectMapper().readValue(
            configFile.toFile(), new TypeReference<Map<String, Entry>>() {});
        return new GrammarRegistry(m);
    }

    /**
     * 言語 key に対応するエントリを取得．
     * @param languageKey 言語キー（例: "java", "java-custom"）
     * @return Entry
     * @throws IllegalArgumentException 言語キーが登録されていない場合
     */
    public Entry get(String languageKey) {
        Entry e = entries.get(languageKey);
        if (e == null) throw new IllegalArgumentException(
            "Unknown tree-sitter language key: " + languageKey
            + "  (define it in treesitter-config.json)");
        return e;
    }

    /**
     * 登録されているすべての言語キーを取得．
     */
    public Set<String> keys() {
        return entries.keySet();
    }
}
