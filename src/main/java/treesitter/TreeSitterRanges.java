package treesitter;

import java.util.Collections;
import java.util.List;

public class TreeSitterRanges {
    public final List<Range> commentRanges;   // COMMENT に分類された範囲
    public final List<Range> stringRanges;    // STRING に分類された範囲
    public final List<Range> reservedRanges;  // RESERVED に分類された範囲

    public TreeSitterRanges(List<Range> c, List<Range> s, List<Range> r) {
        // すべて startByte 昇順でソート済みであることを前提とする（二分探索のため）
        this.commentRanges  = Collections.unmodifiableList(c);
        this.stringRanges   = Collections.unmodifiableList(s);
        this.reservedRanges = Collections.unmodifiableList(r);
    }

    public boolean isInComment(int offset) { return contains(commentRanges, offset); }
    public boolean isInString(int offset)  { return contains(stringRanges, offset); }
    public boolean isReserved(int offset)  { return contains(reservedRanges, offset); }

    /** offset を含む string Range の endByte を返す（含まれなければ -1）． */
    public int endOfStringAt(int offset) { return endAt(stringRanges, offset); }
    /** offset を含む comment Range の endByte を返す（含まれなければ -1）． */
    public int endOfCommentAt(int offset) { return endAt(commentRanges, offset); }

    private static boolean contains(List<Range> rs, int o) {
        int lo = 0, hi = rs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Range r = rs.get(mid);
            if (o < r.startByte)      hi = mid - 1;
            else if (o >= r.endByte)  lo = mid + 1;
            else                      return true;
        }
        return false;
    }

    private static int endAt(List<Range> rs, int o) {
        int lo = 0, hi = rs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Range r = rs.get(mid);
            if (o < r.startByte)      hi = mid - 1;
            else if (o >= r.endByte)  lo = mid + 1;
            else                      return r.endByte;
        }
        return -1;
    }
}
