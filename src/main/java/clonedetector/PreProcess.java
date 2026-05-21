package clonedetector;

import clonedetector.classlist.CommentRule;
import clonedetector.classlist.Pre;
import clonedetector.classlist.Token;
import common.JudgeCharset;
import treesitter.TreeSitterRanges;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.regex.Pattern;

import static common.TokenName.*;

public class PreProcess {
    private String filename;
    private OptionReader or;

    /**
     * tokenList Tokenのリスト
     */
    public ArrayList<Token> tokenList;
    /**
     * preList ccfxprepのリスト
     */
    public ArrayList<Pre> preList = new ArrayList<>();

    private boolean doEscape, lineendEscape = true;

    /* isComment() で使用するグローバル変数 */
    private ArrayList<CommentRule> ruleList;
    private ArrayList<CommentRule> ruleList_Line;
    private ArrayList<CommentRule> literalList;
    private ArrayList<String> reservedWordList;

    private int commentRuleSize;
    private int commentRuleSize_Line;
    private int literalRuleSize;
    private int startLength = 0;
    private int endLength = 0;
    private String commentStart;
    private String commentEnd;
    private int commentType;

    private boolean spaceIndent;
    //private String variableRegex;
    private Pattern p;

    private boolean doZero = false;

    /**
     * ZeroToken のためのグローバル変数
     */
    private int braceCount = 0;
    private int parenCount = 0;
    private String beforeToken = "";
    private int beforeType = 0;

    int nowLine;// 行数
    private int lastNewLine = 0;
    private int nowLineTmp;
    private int lastNewLineTmp;
    private char lineContinue = '\\';

    private boolean space = false;

    // === tree-sitter モード用のフィールド ===
    private TreeSitterRanges tsRanges = null;
    private boolean skipZeroToken = false;

    PreProcess(OptionReader or, String language, String filename) {
        this.or = or;
        //System.out.println(language);
        if (language == null) {
            System.out.println(filename);
        }
        if (language != null && (language.equals("java") || language.equals("c") || language.equals("cpp")))
            doZero = true;

        this.filename = filename;
        doEscape = or.languageRuleMap.get(language).doEscape;
        tokenList = new ArrayList<>();
        ruleList = or.languageRuleMap.get(language).commentRuleList;
        ruleList_Line = or.languageRuleMap.get(language).commentRuleList_Line;
        literalList = or.languageRuleMap.get(language).literalRuleList;
        reservedWordList = or.languageRuleMap.get(language).reservedWordList;
        spaceIndent = or.isSpaceIndent();
        commentRuleSize = ruleList.size();
        commentRuleSize_Line = ruleList_Line.size();
        literalRuleSize = literalList.size();
        if (or.languageRuleMap.get(language).lineContinue != null) {
            lineContinue = or.languageRuleMap.get(language).lineContinue.charAt(0);
        } else {
            lineendEscape = false;
        }

        p = Pattern.compile(or.getVariableRegex());
    }

    /**
     * tree-sitter モード用 hook: 範囲情報を注入し，skipZeroToken を有効化．
     */
    public void setTreeSitterRanges(TreeSitterRanges r) {
        if (r == null) {
            throw new IllegalArgumentException("TreeSitterRanges must not be null");
        }
        this.tsRanges = r;
        this.skipZeroToken = true;  // ANTLR モードと揃え，zeroTokenCheck を無効化
        this.doZero = false;        // 念のため二重ガード
    }

    /**
     * called by Lexer.java
     */
    void readFile() {
        //ソースコード読み込み
        String str = "";
        try {
            str = JudgeCharset.readAll(filename, or.getCharset());
        } catch (IOException e) {
            e.printStackTrace();
        }
        tokenize(str);
        zeroTokenAdd("eof", nowLine, str.length() - lastNewLine, str.length());
    }

    /**
     * トークン分割
     *
     * @param str ソースコード（ファイルそのまま）
     */
    private void tokenize(String str) {
        int i = 0;
        int tmpIndex;
        int startLine;
        int startClm;
        int startIndex;

        int indentNum = 0;
        LinkedList<Integer> indentStack = new LinkedList<>();
        indentStack.add(0);

        while (i < str.length()) {
            char c = str.charAt(i);
            nowLineTmp = nowLine;
            lastNewLineTmp = lastNewLine;
            startLine = nowLine;
            startIndex = i;
            startClm = i - lastNewLine;
            if (startClm == 0) {
                space = true;
                indentNum = 0;
            }
            // 空白タブ文字無視
            if (c == '\u0020' || c == '\t' || c == '\u00A0' || c == '\u3000') {
                i++;
                if (space) indentNum += c == '\t' ? 4 : 1;
            } else {
                if (space) {
                    int lastIndent = indentStack.getLast();
                    if (lastIndent < indentNum) {
                        indentStack.add(indentNum);
                        if (spaceIndent) zeroTokenAdd("blockStart", startLine, startClm, i);
                    } else {
                        while (indentStack.getLast() > indentNum) {
                            indentStack.removeLast();
                            if (spaceIndent) zeroTokenAdd("blockEnd", startLine, startClm, i);
                        }
                    }
                }

                // 行コメント
                if (space && i != (tmpIndex = isLINE(str, i))) {
                    i = tmpIndex;
                }

                // 継続文字
                else if (i != (tmpIndex = isEscapeAndLine(str, i))) {
                    tmpToTrue();
                    i = tmpIndex;
                }

                // 改行文字
                else if (i != (tmpIndex = isNewLine(str, i))) {
                    tmpToTrue();
                    i = tmpIndex;
                }

                //コメント
                else if (i != (tmpIndex = isComment(str, i))) {
                    if (tsRanges != null) advancePosition(str, i, tmpIndex);
                    i = tmpIndex;
                }

                // literal
                else if (i != (tmpIndex = isLiteral(i, str))) {
                    if (tsRanges != null) advancePosition(str, i, tmpIndex);
                    i = tmpIndex;
                    String tmpStr = str.substring(startIndex, i);
                    tokenRegister(tmpStr, startLine, startClm, nowLine, i - lastNewLine, startIndex, i, STRING);
                }

                // 英数字
                else if (p.matcher(str.substring(i, i + 1)).matches()) {
                    i++;
                    while (i < str.length()) {
                        if (i != (tmpIndex = isEscapeAndLine(str, i))) {
                            tmpToTrue();
                            i = tmpIndex;
                        } else if (p.matcher(str.substring(startIndex, i + 1)).matches()) {
                            i++;
                        } else {
                            break;
                        }
                    }
                    String strTmp = str.substring(startIndex, i);
                    tokenRegister(strTmp, startLine, startClm, nowLine, i - lastNewLine, startIndex, i,
                            Character.isDigit(strTmp.charAt(0)) ? NUMBER : isReserved(strTmp, startIndex) ? RESERVE : IDENTIFIER);
                }

                //それ以外（記号など）
                else {
                    if ((int) c != 65279) {
                        tokenRegister(str.substring(startIndex, startIndex + 1), startLine, startClm, nowLine, i - lastNewLine, startIndex, i + 1, SYMBOL);
                    }
                    i++;
                }
                space = false;
            }
        }

    }

    private int isEscapeAndLine(String str, int i) {
        if (lineendEscape && str.charAt(i) == lineContinue) {
            int x;
            if (i + 1 != (x = isNewLine(str, i + 1))) {
                return x;
            }
        }
        return i;
    }

    private int isNewLine(String str, int i) {
        if (i >= str.length()) {
            return lastLineAndNewLine(i);
        }
        if (str.charAt(i) == '\n') {
            return lastLineAndNewLine(i + 1);
        } else if (str.charAt(i) == '\r') {
            if (i + 1 < str.length()) {
                if (str.charAt(i + 1) == '\n') {
                    return lastLineAndNewLine(i + 2);
                }
            }
            nowLineTmp++;
            return i + 1;
        }
        return i;
    }

    private int lastLineAndNewLine(int i) {
        lastNewLineTmp = i;
        nowLineTmp++;
        return i;
    }

    private void tmpToTrue() {
        lastNewLine = lastNewLineTmp;
        nowLine = nowLineTmp;
    }

    private void advancePosition(String str, int from, int to) {
        for (int j = from; j < to && j < str.length(); j++) {
            if (str.charAt(j) == '\n') {
                nowLine++;
                lastNewLine = j + 1;
            } else if (str.charAt(j) == '\r') {
                if (j + 1 < to && j + 1 < str.length() && str.charAt(j + 1) == '\n') {
                    j++;
                    lastNewLine = j + 1;
                } else {
                    lastNewLine = j + 1;
                }
                nowLine++;
            }
        }
    }

    private int isLiteral(int i, String str) {
        // === tree-sitter hook: 範囲ベースの文字列判定 ===
        if (tsRanges != null) {
            if (tsRanges.isInString(i)) {
                int end = tsRanges.endOfStringAt(i);
                return Math.min(end, str.length());
            }
            return i;
        }
        // ↓ 既存ロジック
        int tmpIndex;
        for (int k = 0; k < literalRuleSize; k++) {
            if (i + literalList.get(k).start.length() - 1 >= str.length()) {
                continue;
            }
            int startLength = literalList.get(k).start.length();
            int endLength = literalList.get(k).end.length();
            if (!str.substring(i, i + startLength).equals(literalList.get(k).start)) {
                continue;
            }

            if (!literalList.get(k).nest) {
                i += startLength - 1;
                while (i + endLength < str.length()) {
                    i++;
                    if (str.substring(i, i + endLength).equals(literalList.get(k).end)) {
                        i += endLength;
                        break;
                    } else if (i != (tmpIndex = isEscapeAndLine(str, i))) {
                        tmpToTrue();
                        i = tmpIndex - 1;
                    } else if (i != (tmpIndex = isNewLine(str, i))) {
                        tmpToTrue();
                        i = tmpIndex;
                        break;
                    } else if (doEscape && str.substring(i, i + 1).equals("\\")) {
                        i++;
                    } else if (i + endLength == str.length()) {
                        System.out.println("Syntax Error <Literal> " + filename);
                    }
                }
            } else {// verbatim literal
                i += startLength - 1;
                while (i + endLength < str.length()) {
                    i++;
                    if (str.substring(i, i + endLength).equals(literalList.get(k).end)) {
                        if (i + endLength * 2 < str.length()) {
                            if (str.substring(i + endLength, i + endLength * 2).equals(literalList.get(k).end)) {
                                i += endLength * 2 - 1;
                                continue;
                            }
                        }
                        i += endLength;
                        break;
                    } else if (i != (tmpIndex = isEscapeAndLine(str, i))) {
                        tmpToTrue();
                        i = tmpIndex - 1;
                    } else if (i != (tmpIndex = isNewLine(str, i))) {
                        tmpToTrue();
                        i = tmpIndex - 1;
                    } else if (i + endLength == str.length()) {
                        System.out.println("Syntax Error <Literal> " + filename);
                    }
                }
            }
        }
        return i;
    }

    private int isComment(String str, int i) {
        // === tree-sitter hook: 範囲ベースのコメント判定 ===
        if (tsRanges != null) {
            if (tsRanges.isInComment(i)) {
                // コメント範囲ならその末尾までスキップ
                int end = tsRanges.endOfCommentAt(i);
                return Math.min(end, str.length());
            }
            return i;  // 通常モードのコメントルールは使わない
        }
        // ↓ 既存ロジック
        for (int rule = 0; rule < commentRuleSize; rule++) {
            commentStart = ruleList.get(rule).start;
            commentEnd = ruleList.get(rule).end;
            commentType = ruleList.get(rule).type;
            startLength = commentStart.length();
            endLength = getEndLength(commentEnd);

            if (i + startLength - 1 >= str.length()) {
                continue;
            }

            if (!str.substring(i, i + startLength).equals(commentStart)) {
                continue;
            }

            if (commentType == START) {// 単一行コメント
                i = commentSTART(str, i);
                break;
            } else if (commentType == START_END) {// 複数行コメント
                i = commentSTARTEND(str, i, rule);
                break;
            }
        }
        return i;
    }

    private int commentSTART(String str, int i) {
        int tmpIndex;
        while (i < str.length()) {
            if (i != (tmpIndex = isEscapeAndLine(str, i))) {
                tmpToTrue();
                i = tmpIndex - 1;
            } else if (i != (tmpIndex = isNewLine(str, i))) {
                tmpToTrue();
                return tmpIndex;
            }
            i++;
        }
        return i;
    }

    private int commentSTARTEND(String str, int i, int rule) {
        int nestCount = 0;
        boolean nest = ruleList.get(rule).nest;
        if (nest)
            nestCount++;
        while (i + endLength < str.length()) {
            i++;
            int tmpIndex;
            if (str.substring(i, i + endLength).equals(commentEnd)) {
                if (nest) {
                    nestCount--;
                }
                if (nestCount == 0) {
                    i += endLength;
                    break;
                }
            } else if (i != (tmpIndex = isNewLine(str, i))) {
                tmpToTrue();
                i = tmpIndex - 1;
            } else if (nest && str.substring(i, i + startLength).equals(commentStart)) {
                nestCount++;
            }
        }

        return i;
    }

    private int isLINE(String str, int i) {
        for (int rule = 0; rule < commentRuleSize_Line; rule++) {
            commentStart = ruleList_Line.get(rule).start;
            commentEnd = ruleList_Line.get(rule).end;
            commentType = ruleList_Line.get(rule).type;
            startLength = commentStart.length();
            endLength = getEndLength(commentEnd);

            if (i + startLength - 1 >= str.length()) {
                continue;
            }

            if (!str.substring(i, i + startLength).equals(commentStart)) {
                continue;
            }

            if (commentType == LINE_START) {// 単一行コメント
                i = commentLINESTART(str, i);
                break;
            } else if (commentType == LINE_START_END) {// 複数行コメント
                i = commentLINESTARTEND(str, i);
                break;
            }
        }
        return i;
    }

    private int commentLINESTART(String str, int i) {
        int tmpIndex;
        while (i < str.length()) {
            if (i != (tmpIndex = isEscapeAndLine(str, i))) {
                tmpToTrue();
                i = tmpIndex;
            }
            if (i != (tmpIndex = isNewLine(str, i))) {
                tmpToTrue();
                i = tmpIndex;
                break;
            }
            i++;
        }
        return i;
    }

    private int commentLINESTARTEND(String str, int i) {
        int tmpIndex;
        boolean check = false;
        while (i < str.length()) {
            if (!check && i != (tmpIndex = isNewLine(str, i))
                    && str.substring(tmpIndex, tmpIndex + endLength).equals(commentEnd)) {
                tmpToTrue();
                check = true;
            } else if (check && i != (tmpIndex = isNewLine(str, i))) {
                tmpToTrue();
                i = tmpIndex;
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * ブロックを簡単に認識して長さのないトークンとして埋め込んでいる
     * cond 条件文の開始
     * loop ループ文の開始
     * func 関数の開始
     * brace カッコの無いif文に対するカッコの補完
     */
    private void zeroTokenCheck(String str, int line, int clm, int sum) {
        if (beforeToken.equals("if")) {
            if (reservedWordList.contains(beforeToken)) {
                zeroTokenAdd("c_cond", line, clm, sum);
            }
        } else if (beforeToken.equals("for") || beforeToken.equals("while") || beforeToken.equals("switch")) {
            if (reservedWordList.contains(beforeToken)) {
                zeroTokenAdd("c_loop", line, clm, sum);
            }
        }

        if (!doZero)
            return;

        if (beforeType == IDENTIFIER && str.equals("(")) {
            zeroTokenAdd("c_func", line, clm, sum);
        }
        if (beforeToken.equals("else") && !str.equals("if")) {
            zeroTokenAdd("c_cond", line, clm, sum);
            if (!str.equals("{")) {
                braceCount++;
                zeroTokenAdd("{", line, clm, sum);
            }
        } else if (beforeToken.equals(";")) {
            while (braceCount > 0) {
                zeroTokenAdd("}", line, clm, sum);
                braceCount--;
            }
        }

        if (str.equals("(")) {
            if (parenCount > 0) {
                parenCount++;
            } else if (beforeToken.equals("if") || beforeToken.equals("while") || beforeToken.equals("for") || beforeToken.equals("switch")) {
                parenCount++;
            }
        }

        if (beforeToken.equals(")")) {
            if (parenCount > 1) {
                parenCount--;
            } else if (parenCount == 1) {
                parenCount = 0;
                if (!str.equals(";") && !str.equals("{")) {
                    braceCount++;
                    zeroTokenAdd("{", line, clm, sum);
                }
            }
        }
    }

    private void tokenListAdd(Token tmp) {
        tokenList.add(tmp);
    }

    private void preListAdd(Pre tmp) {
        preList.add(tmp);
    }

    private void tokenRegister(String str, int lineS, int clmS, int lineE, int clmE, int sumStart, int sumEnd, int type) {
        if (!skipZeroToken) zeroTokenCheck(str, lineS, clmS, sumStart);  // ← tree-sitter モードではスキップ
        tokenListAdd(new Token(str, lineS, clmS, lineE, clmE, type));
        preListAdd(new Pre(str, lineS, clmS, lineE, clmE, type, sumStart, sumEnd));

        beforeToken = str;
        beforeType = type;
    }

    private void zeroTokenAdd(String str, int lineS, int clmS, int sum) {
        tokenListAdd(new Token(str, lineS, clmS, lineS, clmS, ZERO));
        preListAdd(new Pre(str, lineS, clmS, lineS, clmS, ZERO, sum, sum));
    }

    private int getEndLength(String end) {
        if (end != null)
            return end.length();
        return 0;
    }

    /**
     * 予約語判定：tree-sitter モードでは範囲ベース，通常モードでは Set ベース．
     */
    private boolean isReserved(String str, int offset) {
        if (tsRanges != null) return tsRanges.isReserved(offset);
        return reservedWordList.contains(str);
    }
}
