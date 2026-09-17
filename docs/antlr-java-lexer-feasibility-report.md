# ANTLR Java Lexer方式の実現可能性調査

## 1. 結論

現行の`-antlr`モードは対象言語のLexer/Parserを生成・ロード・実行しない。実行しているANTLRコードは、同梱の`ANTLRv4Lexer`/`ANTLRv4Parser`で`.g4`ファイルそのものを解析する処理である。抽出したルールをJava `Pattern`向け文字列へ変換し、対象ソースには正規表現コメント除去と独自の単純字句分割を適用する。

同梱ANTLR 4.7.1でLua、PHP、C#の文法からJava Lexerを実際に生成・コンパイル・実行できた。生成LexerはLuaの長括弧、PHPのmode/channel、C#の逐語・補間文字列を文法本来の状態機械で扱えた。したがって、現在の問題のうち「文法の意味を正規表現へ潰したこと」に起因する範囲は、直接利用方式で解決できる見込みが高い。ただし、コメントの`skip`、CCFinderSW独自の予約語分類、文字列を1字句へまとめる仕様、文法ごとのJava action/base class依存は別途設計が必要である。

## 2. 現行`-antlr`のデータフロー

1. `CloneDetector.start`が`OptionReader.ANTLRInitializer()`を呼ぶ。
2. `ANTLRInitializer`は`grammarsv4/<language>`を選び、`new Aleesa().getGrammar(...)`を呼ぶ。
3. `Aleesa.createParser`は同梱の`aleesa.ANTLRv4Parser.ANTLRv4Lexer`と`ANTLRv4Parser`で各`.g4`を解析する。これは対象言語のLexerではなく、ANTLR文法記述用のLexer/Parserである。
4. `Aleesa.explore`がlexer rule、fragment、リテラル、rule参照を収集する。
5. `combineRegexToSkip`が`->skip`、`->channel(HIDDEN)`、`comment`を含むルール、`channel(...COMMENT...)`を選び、文字列名に`string`を含むルールを`strRegex`候補にする。リテラル候補は予約語になる。
6. `buildExpressionToSkip`が参照を再帰的に展開する。同一キーの再出現は`!keyList.contains(str)`で展開せず、特別な再帰形だけを否定先読みを含む近似へ置換する。
7. `Lexer.tokenizeAFileANTLR`が`skipRegex`/`strRegex`を`CommentsRemoverUsingRegex`へ渡す。コメントにマッチした範囲は空白へ置換され、文字列は保護される。
8. `PreProcessEase.tokenizeANTLRAvoidStr`が英数字列をまとめ、その他を基本的に1文字ずつ分割する。予約語は`reservedRegex`で分類され、CCFX prepが出力される。

主要箇所: [CloneDetector.java](../src/main/java/clonedetector/CloneDetector.java)、[OptionReader.java](../src/main/java/clonedetector/OptionReader.java)、[Aleesa.java](../src/main/java/aleesa/Aleesa.java)、[Lexer.java](../src/main/java/clonedetector/Lexer.java)、[CommentsRemoverUsingRegex.java](../src/main/java/aleesa/CommentsRemoverUsingRegex.java)、[PreProcessEase.java](../src/main/java/aleesa/PreProcessEase.java)。

## 3. 生成・実行の有無

| 対象 | 現行CCFinderSW | 今回の実験 |
|---|---|---|
| 対象言語Java Lexer/Parserの生成 | なし。Gradleの`generateGrammarSource`、`org.antlr.v4.Tool`、JavaCompiler、ClassLoader経路なし | ANTLR 4.7.1でLua/PHP/C# Lexerを生成 |
| 対象ソースでの実行 | なし。Regexと`PreProcessEase`のみ | 生成Lexerを直接実行し、token type/name/text/channel/位置を出力 |
| `.g4`解析 | あり。同梱生成済みANTLR文法用Lexer/Parserを実行 | 生成対象文法とは別物 |

対象言語の文法は`CCFinderSW-1.0/grammarsv4`にも`src/main/dist/grammarsv4`にも存在するが、ビルド依存は`lib/antlr-4.7.1-complete.jar`をクラスパスへ載せるだけで、対象文法の生成タスクはない。実行時ログには、同梱の`.g4`がANTLR 4.5.1生成物であるのにランタイム4.7.1を使っている警告も出た。

## 4. 問題が発生するコード上の理由

### 再帰・計数

Luaの`LONGSTRING : '[' NESTED_STR ']'`と、`NESTED_STR : '=' NESTED_STR '=' | '[' .*? ']'`は開始と終了の`=`数を対応させる。Aleesaは同じruleを`keyList`で再展開しないため、実際のLuaログの`skip`/`string`には`(NESTED_STR)`が残った。正規表現抽出は任意の計数を保持できず、`CommentsRemoverUsingRegex`はその不完全なPatternを全文へ適用する。

### 過剰適用

`combineRegexToSkip`はルール名の`comment`、末尾アクションの`skip`/`channel(HIDDEN)`、channel名の`COMMENT`を選択する。選ばれた複数ルールは`builderSkip`へ`|`で結合され、modeやANTLRのルール順序・最長一致は保持されない。`string`も名前ヒューリスティックで集められる。PHPではこの結合結果が`<?php`外にも適用され、今回の混在入力で現行Token=1になった。

### mode・状態

PHP文法は`pushMode`/`popMode`、`_heredocIdentifier`、`nextToken`のactionを使う。C#文法は補間文字列の深さ、verbatim状態、curly levelをJavaフィールドへ保存し、modeを切り替える。現行抽出処理はaction、predicate、mode、mode stack、`more`、`type`を実行せず、これらを含むルール断片を単一の状態なしPatternへ近似する。そのため開始側で得た識別子や現在modeを終了側で照合できない。

## 5. 実験結果

入力は[long-brackets.lua](../experiments/antlr-java-lexer/inputs/long-brackets.lua)、[mixed.php](../experiments/antlr-java-lexer/inputs/mixed.php)、[strings.cs](../experiments/antlr-java-lexer/inputs/strings.cs)である。

| 問題 | 対象言語・入力 | 現行`-antlr` | 生成Java Lexer | 解決可否 | 根拠 |
|---|---|---|---|---|---|
| 再帰・計数 | Lua long bracket | `NESTED_STR`がRegexに残り、Token=22。長括弧は単文字列へ分解 | `COMMENT` channel=1、`LONGSTRING` 1トークン | 生成Lexerで解決 | Lexerがrule再帰と対応する境界を実行 |
| mode・状態 | PHP/HTML/CSS/PHP混在 | `skipRegex`適用後Token=1。modeを保持しない | `HtmlStyleOpen`、`StyleBody`、`PHPStart`、PHP `Comment` channelなどを出力 | mode起因は解決見込み | 生成Lexerがmode/action/channelを実行。ただしCSS内容はこのPHP文法の`StyleBody`扱い |
| 文字列境界 | C#逐語・補間 | Token=20。Regexは`$"`/`$@"`等を近似し、CCFinderSW分類へ再分割 | `VERBATIUM_STRING`は1トークン、補間はstart/inside/endの状態付き列 | 境界は解決、CCFinder仕様は未解決 | Lexer出力に`INTERPOLATED_VERBATIUM_STRING_START`等が出現 |
| ルール過剰適用 | PHP混在入力、C#コメント候補 | 複数ルールを単一skip Patternへ結合。PHPで実害Token=1 | modeごとに候補ルールを限定し、コメントをchannelで区別 | 生成Lexerで解決見込み | PHPの`Comment` channel=2、HTML/CSS/PHPトークンが分離 |

PHP文法は今回の最小混在入力ではheredocを含めていないため、heredoc終端識別子の正否そのものは直接比較していない。文法ソースの`_heredocIdentifier`と`CheckHeredocEnd`から、生成Lexer側が状態を保持することは確認できる。現行方式のPHP失敗（Token=1）はmode/actionを使うより前にskipRegexが広くマッチすることを実行ログで確認した。

生成Lexerの代表出力:

```text
Lua: COMMENT channel=1 ... --[=[ comment ]=]
Lua: LONGSTRING channel=0 ... [==[\ntext\n]==]
PHP: HtmlStyleOpen, StyleBody, PHPStart, Comment channel=2, Echo, DoubleQuote, StringPart
C#: VERBATIUM_STRING ... @"a ""quote"" b // not comment"
C#: INTERPOLATED_VERBATIUM_STRING_START ... $@"
```

## 6. 独立プロトタイプ

[run.sh](../experiments/antlr-java-lexer/run.sh)は文法をANTLR 4.7.1で生成し、生成Javaをコンパイルして`TokenDump`を実行する。[TokenDump.java](../experiments/antlr-java-lexer/TokenDump.java)はtoken index、数値type、symbolic name、text、channel、line/column、start/stop offsetを出力する。ANTLR 4.7.1の`Lexer`には公開`getMode()`がないため、モード値は出力していない。modeの効果はmode遷移後に出るトークン名とchannelで観測した。

自動検証は[verify.sh](../experiments/antlr-java-lexer/verify.sh)で行う。生成物とclassは`/tmp/ccfsw-antlr-java-lexer`以下へ置き、リポジトリへコミットしない。

実行例:

```sh
sh experiments/antlr-java-lexer/verify.sh
java -jar build/libs/CCFinderSW-1.0.jar D -d experiments/antlr-java-lexer/inputs -l lua -o /tmp/ccfsw-current-lua -antlr lua -ccfsw pair
java -jar build/libs/CCFinderSW-1.0.jar D -d experiments/antlr-java-lexer/inputs -l php -o /tmp/ccfsw-current-php -antlr php -ccfsw pair
java -jar build/libs/CCFinderSW-1.0.jar D -d experiments/antlr-java-lexer/inputs -l csharp -o /tmp/ccfsw-current-csharp -antlr cs -ccfsw pair
```

## 7. 直接利用を統合する候補

最小実装は、既存Regex経路を変更せず、実験用Lexer adapterを追加する方式である。Lexerは`CommonTokenStream`ではなく、まず直接`nextToken()`を観測する。`skip()`されたコメントはTokenStreamに出ないため、文法変更なしで必要なら生成Lexerを継承して`nextToken`前後、またはコメントchannelを利用する。CCFinderSW側には、コメントchannelを空白範囲、文字列tokenを`STRING`、その他を既存の`Pre`/`Token`へ変換するadapterを設ける。予約語はliteral nameではなく、文法のtoken typeと既存の予約語設定を対応付ける方が安全である。

| 管理方法 | 実装量 | 移植性/性能 | 更新容易性 | 再現性・リスク |
|---|---:|---|---|---|
| 生成Javaをリポジトリへ含める | 小-中 | 高速、文法ごとに依存を同梱 | 文法更新時に再生成 | 差分が大きく、生成物の古さがリスク |
| ビルド時生成 | 中 | 高速、更新しやすい | ANTLR/Java環境を固定 | 生成差分と依存解決を固定する必要 |
| 実行時生成・コンパイル・ロード | 大 | 初回遅く、権限/性能リスク | 最も柔軟 | 未信頼`.g4`の実行・コンパイルがセキュリティリスク |
| 事前生成JARプラグイン | 中-大 | 実行は高速、分離可能 | 文法ごと更新 | API/ABIと署名・供給元管理が必要 |

推奨は、まずビルド時に対象文法から生成し、文法・ANTLR・Javaバージョンを固定した実験adapterを追加すること。生成Lexerが`skip`するコメントを観測可能なchannelへ変換した実験文法は、本番文法を破壊せず別コピーで管理する。Parserはコメント・文字列・予約語境界だけなら不要で、文脈依存分類が必要になった場合に限定して追加する。

## 8. 理由、制約、残課題

READMEはCCFinderSWが言語別Lexerを持たず、optional fileで言語ごとの字句解析を調整すると説明している。コミット履歴（`b1cfea4`初期、`bd0ca93` 0.9.1、`c375728` 1.0）とコード内コメントから、対象言語Lexerを実行しない明示的な設計理由は確認できなかった。確認できた事実は「柔軟な多言語tokenizationのためRegex/option file方式を採用している」までであり、生成Lexerを避けた理由は推測できても断定できない。

残課題は、PHP heredoc/nowdocを含む完全な直接比較、C#補間文字列をCCFinderSWで1字句へまとめる仕様、全言語のJava action/base class/import依存、ANTLR 4.5.1生成文法と4.7.1 runtimeのバージョン固定、コメント範囲とoffsetをCCFX prepへどう保存するかである。生成Lexer方式でも、文法がJavaターゲット非対応、base class不足、依存grammar不足、actionのJava以外ターゲット記法を含む場合は個別対応が必要である。

## 9. 実行コマンドと変更ファイル

環境はJava 17系、Gradle wrapper、ANTLR `lib/antlr-4.7.1-complete.jar`を使用した。実行した主なコマンドは次の通り。

```sh
java -jar lib/antlr-4.7.1-complete.jar -o /tmp/ccfsw-antlr-probe/lua CCFinderSW-1.0/grammarsv4/lua/Lua.g4
java -jar lib/antlr-4.7.1-complete.jar -o /tmp/ccfsw-antlr-probe/php CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4
java -jar lib/antlr-4.7.1-complete.jar -o /tmp/ccfsw-antlr-probe/csharp CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4
sh experiments/antlr-java-lexer/verify.sh
sh ./gradlew test
```

追加ファイル:

- `docs/antlr-java-lexer-feasibility-report.md`
- `experiments/antlr-java-lexer/TokenDump.java`
- `experiments/antlr-java-lexer/run.sh`
- `experiments/antlr-java-lexer/verify.sh`
- `experiments/antlr-java-lexer/inputs/long-brackets.lua`
- `experiments/antlr-java-lexer/inputs/mixed.php`
- `experiments/antlr-java-lexer/inputs/strings.cs`

既存CCFinderSW本体、同梱文法、生成物は変更していない。