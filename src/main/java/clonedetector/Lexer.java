package clonedetector;

import aleesa.CommentsRemoverUsingRegex;
import aleesa.PreProcessEase;
import clonedetector.classlist.FileData;
import common.FileAndString;
import common.PrintProgress;
import common.Time;
import treesitter.TreeSitterRanges;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.stream.IntStream;

/**
 * クローン検出対象ファイルをディレクトリ内から検索し，
 * 各ファイルのPreProcessを呼ぶ役割
 */
public class Lexer {
    public OptionReader or;
    private FileData fd;
    private String language;

    public Lexer(OptionReader or, FileData filedata) {
        this.or = or;
        this.fd = filedata;
        fd.directoryName = or.getDirectory();
        this.language = or.getLanguage();
        fd.filePathList = new ArrayList<>();
        fd.fileNameList = new ArrayList<>();
    }

    /**
     * call by CloneDetector.java
     * searchDirectory -> pre process
     */

    public void doPreProcess() {
        if (fd.filePathList.size() == 0) {
            System.out.println("No Target File");
            return;
        }

        int max = fd.filePathList.size();
        fd.NGramIndexList = new int[max];
        int[] tmpNGram = new int[max];
        fd.tokenCountList = new int[max];
        fd.tokenIndexList = new int[max];
        fd.lineCountList = new int[max];

        PrintProgress ps = new PrintProgress(2);
        IntStream.range(0, max)
                //.parallel()
                .forEach(i -> {
                    String path = fd.filePathList.get(i);
                    String extension = path.substring(path.lastIndexOf('.') + 1);

                    if (or.isTreeSitterMode()) {
                        tokenizeAFileTreeSitter(path, i);
                    } else if (or.isANTLRMode()) {
                        tokenizeAFileANTLR(path, i);
                    } else {
                        tokenizeAFile(path, i, or.extensionMapTrueEnd.get(extension));
                    }
                    ps.plusProgress(max);
                    tmpNGram[i] = Math.max(fd.tokenCountList[i] - or.getN() + 1, 0);
                });
        fd.NGramCountList = tmpNGram;
        fd.tokenIndexList[0] = 0;
        for (int i = 1; i < fd.filePathList.size(); i++) {
            fd.tokenIndexList[i] = fd.tokenCountList[i - 1] + fd.tokenIndexList[i - 1];
        }
        fd.NGramIndexList[0] = 0;
        for (int i = 1; i < max; i++) {
            fd.NGramIndexList[i] = fd.NGramCountList[i - 1] + fd.NGramIndexList[i - 1];
        }
    }

    /**
     * search object files
     */
    public void searchDirectory() {
        Time time = new Time();
        String relativePath = "..";
        try {
            final String s = Paths.get(relativePath).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
            File d = new File(fd.directoryName);
            fd.directoryNameAbsolute = d.getAbsolutePath();
        } catch (IOException e) {
            System.out.println("out");
        }
        ArrayList<String> fileList = FileAndString.searchDirectory(fd.directoryName);
        for (String x : fileList) {
            if (x.contains("\\.ccfxprepdir\\")) {
                continue;
            }
            File aFile = new File(x);
            int last = x.lastIndexOf(".");
            String tmp = x.substring(last + 1);
            if (aFile.isFile() && last != -1 && (or.extensionList.contains(tmp) 
                    || (or.isANTLRMode() && tmp.matches(or.getExtensionRegex()))
                    || (or.isTreeSitterMode() && tmp.matches(or.getExtensionRegex())))) {
                fd.filePathList.add(aFile.getPath());
                fd.fileNameList.add(aFile.getAbsolutePath());
            }
        }
        System.out.println("Search Directory " + time.end());
    }

    public void outputFileList() {
        StringBuilder buf = new StringBuilder();
        IntStream.rangeClosed(0, fd.filePathList.size() - 1).forEach(i -> {
            buf.append(i).append("\t").append(fd.filePathList.get(i))
                    .append("\t").append(fd.lineCountList[i])
                    .append("\t").append(fd.tokenCountList[i])
                    .append("\t").append(fd.fileNameList.get(i))
                    .append("\n");
        });
        FileAndString.writeAll(getFileListPath(), buf.toString());
    }

    /**
     * nolexer 指定時
     */
    public void loadLexer() {
        String filename = getFileListPath();
        System.out.println("Load Lexer from " + filename);
        String relativePath = "..";
        try {
            final String s = Paths.get(relativePath).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
            File d = new File(fd.directoryName);
            fd.directoryNameAbsolute = d.getAbsolutePath();
        } catch (IOException e) {
            System.out.println("out");
        }

        String str = "";
        try {
            str = FileAndString.readAll(filename);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String[] x = str.split("\r\n?|\n");
        fd.NGramIndexList = new int[x.length];
        fd.NGramCountList = new int[x.length];
        fd.tokenCountList = new int[x.length];
        fd.tokenIndexList = new int[x.length];
        fd.lineCountList = new int[x.length];

        for (int i = 0; i < x.length; i++) {
            String[] y = x[i].split("\t");
            fd.filePathList.add(y[1]);
            fd.lineCountList[i] = Integer.parseInt(y[2]);
            fd.tokenCountList[i] = Integer.parseInt(y[3]);
            fd.fileNameList.add(y[4]);
            fd.NGramCountList[i] = Math.max(fd.tokenCountList[i] - or.getN() + 1, 0);
        }
        fd.tokenIndexList[0] = 0;
        for (int i = 1; i < fd.filePathList.size(); i++) {
            fd.tokenIndexList[i] = fd.tokenCountList[i - 1] + fd.tokenIndexList[i - 1];
        }
        fd.NGramIndexList[0] = 0;
        for (int i = 1; i < fd.filePathList.size(); i++) {
            fd.NGramIndexList[i] = fd.NGramCountList[i - 1] + fd.NGramIndexList[i - 1];
        }
    }

    /**
     * call lexer
     *
     * @param filename filepath
     * @param i        file number
     * @param language (refer to extensionMapTrueEnd)
     */
    public void tokenizeAFile(String filename, int i, String language) {// 通常使用
        PreProcess pp = new PreProcess(or, language, filename);
        pp.readFile();

        if (language.equals("c") || language.equals("cpp")) {
            ParserEase pr = new ParserEase();
            pp.preList = pr.removeTokensC(pp.preList);
            pp.tokenList = pr.convertPreToken(pp.preList);
        }
        //make prepFile
        new CCFXPrepOutput(or.getDirectory(), pp.preList, or.getLanguage()).outputCCFXPrep(filename);

        plusLineCount(pp.nowLine, pp.tokenList.size());
        fd.lineCountList[i] = pp.nowLine;
        fd.tokenCountList[i] = pp.tokenList.size();
    }

    private void tokenizeAFileANTLR(String eachTargetPath, int i) {
        //necessary skipRegex
        String removedSource = or.als.isRecursive
                ? CommentsRemoverUsingRegex.getCommentRemoverRecursive(eachTargetPath, or.getCharset(), or.als.skipRegex, or.als.strRegex)
                : CommentsRemoverUsingRegex.getCommentRemover(eachTargetPath, or.getCharset(), or.als.skipRegex, or.als.strRegex);

        //necessary reservedRegex
        PreProcessEase ppe = new PreProcessEase(or.als.reservedRegex, or.als.strRegex);

        //tokenization, make ppe.tokenList!
        ppe.tokenizeANTLRAvoidStr(removedSource);

        //ccfx prep file output
        new CCFXPrepOutput(or.getDirectory(), ppe.preList, language).outputCCFXPrep(eachTargetPath);

        plusLineCount(ppe.nowLine, ppe.tokenList.size());
        fd.lineCountList[i] = ppe.nowLine;
        fd.tokenCountList[i] = ppe.tokenList.size();
    }

    /**
     * tree-sitter モード用：query で取得した範囲情報を使用したトークン化．
     */
    private void tokenizeAFileTreeSitter(String filename, int i) {
        try {
            // 1. tree-sitter で範囲抽出
            TreeSitterRanges ranges = or.tsExtractor.extract(filename, or.getCharset());
            if (ranges == null) {
                throw new IllegalStateException("tree-sitter ranges are null: " + filename);
            }

            // 2. PreProcess を range 注入モードで初期化
            PreProcess pp = new PreProcess(or, or.getLanguage(), filename);
            pp.setTreeSitterRanges(ranges);
            pp.readFile();

            // 3. prep ファイル出力（既存と同じ）
            new CCFXPrepOutput(or.getDirectory(), pp.preList, or.getLanguage())
                    .outputCCFXPrep(filename);
            plusLineCount(pp.nowLine, pp.tokenList.size());
            fd.lineCountList[i] = pp.nowLine;
            fd.tokenCountList[i] = pp.tokenList.size();
        } catch (Exception e) {
            throw new RuntimeException("tree-sitter preprocessing failed: " + filename, e);
        }
    }

    private String getFileListPath() {
        return fd.directoryName + File.separator + "filelist_" + language + ".txt";
    }

    private synchronized void plusLineCount(int line, int token) {
        fd.lineCount += line;
        fd.tokenCount += token;
    }
}
