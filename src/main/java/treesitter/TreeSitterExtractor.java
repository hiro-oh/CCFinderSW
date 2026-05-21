package treesitter;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TreeSitterExtractor {
    private final String tsLanguageName;         // tree-sitter 側の言語名（例: "java"）
    private final Path queryFile;                // highlights.scm or ccfsw.scm
    private final Path grammarDir;               // treesitter-grammars/tree-sitter-<lang>/
    private final CaptureCategories categories;  // capture 名 → 分類

    public TreeSitterExtractor(String tsLanguageName, Path queryFile, Path grammarDir,
                               CaptureCategories categories) {
        this.tsLanguageName = tsLanguageName;
        this.queryFile = queryFile;
        this.grammarDir = grammarDir;
        this.categories = categories;
    }

    /**
     * 1ファイル分の query を実行し，範囲リストを返す．
     * tree-sitter CLI を ProcessBuilder で呼ぶ方式（プロトタイプ）．
     * @param filePath ソースファイルのパス
     * @param charset 文字コード（例: "UTF-8"）
     * @return TreeSitterRanges 解析結果
     */
    public TreeSitterRanges extract(String filePath, String charset)
            throws IOException, InterruptedException {
        List<CaptureRow> rows = runQueryCli(filePath);

        List<Range> comments = new ArrayList<>();
        List<Range> strings  = new ArrayList<>();
        List<Range> reserved = new ArrayList<>();

        // 分類ルールは CaptureCategories に委譲
        for (CaptureRow row : rows) {
            switch (categories.classify(row.captureName)) {
                case COMMENT:  comments.add(row.range);  break;
                case STRING:   strings.add(row.range);   break;
                case RESERVED: reserved.add(row.range);  break;
                case NONE:     /* 除外 */            break;
            }
        }

        sortByStart(comments);
        sortByStart(strings);
        sortByStart(reserved);
        return new TreeSitterRanges(comments, strings, reserved);
    }

    private List<CaptureRow> runQueryCli(String filePath)
            throws IOException, InterruptedException {
        // tree-sitter query を実行
        ProcessBuilder pb = new ProcessBuilder(
            "tree-sitter", "query",
            "-p", grammarDir.toString(),
            queryFile.toString(),
            filePath,
            "--captures"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<CaptureRow> rows = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        boolean queryError = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            int[] lineStarts = computeLineStarts(filePath);
            while ((line = br.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (line.contains("Error:")
                        || line.contains("Query compilation failed")
                        || line.contains("No language found")
                        || line.contains("Failed to")) {
                    queryError = true;
                }
                if (!line.startsWith("    pattern:")) continue;  // サマリー・ファイル名行・Warning を除外
                CaptureRow row = parseCaptureLine(line, lineStarts);
                if (row != null) rows.add(row);
            }
        }
        int exitCode = p.waitFor();
        if (exitCode != 0 || queryError) {
            throw new IOException("tree-sitter query failed"
                    + " (exit=" + exitCode + ")"
                    + " file=" + filePath
                    + " grammar=" + grammarDir
                    + " query=" + queryFile
                    + System.lineSeparator()
                    + output);
        }
        return rows;
    }

    /**
     * 標準出力行をパース: "pattern: N, capture: M - <name>, start: (r, c), end: (r, c), text: `...`"
     * サンプル:
     *   pattern:  0, capture:  0 - keyword,  start: (0, 0),  end: (0, 3),  text: `def`
     *   pattern: 13, capture: 10 - comment,  start: (10,16), end: (10,45), text: `# comment`
     */
    private CaptureRow parseCaptureLine(String line, int[] lineStarts) {
        try {
            // "pattern: N, capture: M - <name>, start: (startRow, startCol), end: (endRow, endCol), text: `...`"
            // から capture 名と座標を抽出
            Pattern p = Pattern.compile(
                "pattern:\\s*\\d+,\\s*capture:\\s*\\d+\\s*-\\s*([\\w.]+),\\s*" +
                "start:\\s*\\((\\d+),\\s*(\\d+)\\),\\s*" +
                "end:\\s*\\((\\d+),\\s*(\\d+)\\)");
            Matcher m = p.matcher(line);
            
            if (!m.find()) return null;
            
            String captureName = m.group(1);
            int startRow = Integer.parseInt(m.group(2));
            int startCol = Integer.parseInt(m.group(3));
            int endRow = Integer.parseInt(m.group(4));
            int endCol = Integer.parseInt(m.group(5));
            
            // 行頭 byte offset から相対的に計算
            int startByte = lineStarts[startRow] + startCol;
            int endByte = lineStarts[endRow] + endCol;
            
            CaptureRow row = new CaptureRow();
            row.captureName = captureName;
            row.range = new Range(startByte, endByte);
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ファイルの各行頭の byte offset を計算．LF/CR のみカウント．
     */
    private int[] computeLineStarts(String filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(filePath));
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void sortByStart(List<Range> list) {
        list.sort(Comparator.comparingInt(r -> r.startByte));
    }

    private static class CaptureRow {
        String captureName;
        Range range;
    }
}
