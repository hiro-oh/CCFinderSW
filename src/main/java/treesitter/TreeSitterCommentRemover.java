package treesitter;

import common.JudgeCharset;
import java.io.IOException;
import java.util.List;

public class TreeSitterCommentRemover {
    /**
     * コメント範囲を空白置換（非空白を ' ' に）．
     * 行・列を保つため改行（LF/CR）や TAB は置換しない．
     * @param path ファイルパス
     * @param charset 文字コード
     * @param commentRanges コメント範囲リスト
     * @return 処理済みのソースコード
     */
    public static String removeComments(String path, String charset, List<Range> commentRanges)
            throws IOException {
        String source = JudgeCharset.readAll(path, charset);
        char[] buf = source.toCharArray();
        for (Range r : commentRanges) {
            int end = Math.min(r.endByte, buf.length);
            for (int i = r.startByte; i < end; i++) {
                char c = buf[i];
                if (c != '\n' && c != '\r' && c != '\t' && !Character.isWhitespace(c)) {
                    buf[i] = ' ';
                }
            }
        }
        return new String(buf);
    }
}
