package treesitter;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * capture-categories.json をロードし，capture 名を COMMENT / STRING / RESERVED に分類．
 * 判定の優先順位:
 *   1. 各カテゴリ内では exact が prefix より優先．
 *   2. カテゴリ間では COMMENT → STRING → RESERVED の順で評価し，先勝ち．
 *   3. いずれにもマッチしなければ NONE．
 */
public class CaptureCategories {
    public enum Category { COMMENT, STRING, RESERVED, NONE }

    private final Map<Category, List<String>> prefixes;
    private final Map<Category, Set<String>>  exacts;

    private CaptureCategories(Map<Category, List<String>> p, Map<Category, Set<String>> e) {
        this.prefixes = p;
        this.exacts = e;
    }

    /**
     * capture-categories.json をロード．
     * スキーマ例:
     * {
     *   "comment":  {"prefix": ["comment"], "exact": []},
     *   "string":   {"prefix": ["string", "character"], "exact": []},
     *   "reserved": {"prefix": ["keyword"], "exact": ["operator.special"]}
     * }
     * @param jsonFile capture-categories.json のパス
     * @return CaptureCategories インスタンス
     */
    public static CaptureCategories load(Path jsonFile) throws Exception {
        Map<String, Map<String, List<String>>> raw =
            new ObjectMapper().readValue(jsonFile.toFile(),
                new TypeReference<Map<String, Map<String, List<String>>>>() {});

        Map<Category, List<String>> prefixes = new EnumMap<>(Category.class);
        Map<Category, Set<String>>  exacts   = new EnumMap<>(Category.class);

        for (Category cat : EnumSet.of(Category.COMMENT, Category.STRING, Category.RESERVED)) {
            Map<String, List<String>> entry =
                raw.getOrDefault(cat.name().toLowerCase(), Map.of());
            List<String> prefixList = entry.getOrDefault("prefix", List.of()).stream()
                .map(String::toLowerCase).collect(Collectors.toList());
            Set<String> exactSet = entry.getOrDefault("exact", List.of()).stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
            prefixes.put(cat, prefixList);
            exacts.put(cat, exactSet);
        }
        return new CaptureCategories(prefixes, exacts);
    }

    /**
     * capture 名を分類する．
     * @param captureName キャプチャ名（例: "keyword", "string.special", "comment"）
     * @return 分類カテゴリ
     */
    public Category classify(String captureName) {
        String c = captureName.toLowerCase();
        
        // 優先順位: COMMENT → STRING → RESERVED
        for (Category cat : EnumSet.of(Category.COMMENT, Category.STRING, Category.RESERVED)) {
            // exact が prefix より優先
            if (exacts.getOrDefault(cat, Set.of()).contains(c)) return cat;
            for (String pre : prefixes.getOrDefault(cat, List.of())) {
                if (c.startsWith(pre)) return cat;
            }
        }
        return Category.NONE;
    }
}
