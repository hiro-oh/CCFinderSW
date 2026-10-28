# ANTLR生成Java Lexer 3情報抽出実験 — 引き継ぎサマリー

## A. 実験の目的

この実験の目的は、ANTLR文法から生成したJava Lexerを実行し、CCFinderSWが必要とする次の3情報を、元文法を正規表現へ復元せずに取得できるか確認することである。

- `COMMENT`: コメントのtoken、channel、またはソース範囲
- `STRING`: 文字列リテラルのtoken、または複数tokenをまとめたソース範囲
- `RESERVED`: 予約語のtoken typeまたは固定literal

現行CCFinderSWの`-antlr`モードは、対象言語の生成Lexerを実行していない。ANTLR文法用の`ANTLRv4Lexer`/`ANTLRv4Parser`で`.g4`自体を解析し、コメント・文字列・予約語候補を抽出してJava正規表現へ変換し、対象ソースへ適用している。この方式では、Luaの計数付きlong bracket、PHPのmode/channel/action、C#の逐語・補間文字列のような状態を持つ字句規則の意味が失われる可能性がある。

したがって、正規表現の完全復元は成功条件ではない。生成Lexerが文法本来の状態機械で認識した結果を直接利用し、将来CCFinderSWの字句解析処理を置き換えられるかを判断することが最終目標である。

## B. 作業前に確認した事実

### 現行`-antlr`の仕組み

`CloneDetector`から`OptionReader.ANTLRInitializer()`が呼ばれ、`Aleesa`が同梱のANTLR文法用Lexer/Parserで`.g4`を解析する。`Aleesa`はlexer rule、fragment、literal、rule参照を集め、`combineRegexToSkip`と`buildExpressionToSkip`で正規表現候補を作る。`Lexer.tokenizeAFileANTLR`は`CommentsRemoverUsingRegex`へ候補を渡し、`PreProcessEase.tokenizeANTLRAvoidStr`が残りを分割する。対象言語のLua/PHP/C# Lexerクラスをこの経路で生成・ロード・実行する処理は確認できなかった。

### 前回までに確認されていた内容

既存報告では、同梱文法からANTLR 4.7.1でLua、PHP、C#のJava Lexerを生成・コンパイル・実行できること、Luaのlong bracket、PHPのmode/channel、C#の逐語・補間文字列を生成Lexerが扱えることを確認していた。現行の正規表現経路では、Luaの再帰・計数構造やPHPのmode依存を状態なしPatternへ落とすため、入力によって過剰適用や境界喪失が起こると整理されていた。

### 環境と文法

- ブランチ: `experiment/antlr-java-lexer`
- HEAD: `ccfba89 Add ANTLR Java lexer feasibility experiment`
- Java: OpenJDK `17.0.20.1`
- Gradle wrapper: Gradle `4.10`系。`./gradlew`は実行権限がないため、`sh ./gradlew test`で実行した
- ANTLR tool/runtime: リポジトリ内の`lib/antlr-4.7.1-complete.jar`（SHA-256: `f41dce7441d523baf9769cb7756a00f27a4b67e55aacab44525541f62d7f6688`）
- Lua文法: `CCFinderSW-1.0/grammarsv4/lua/Lua.g4`、SHA-256: `29b2348f6351995467bebc33ec162cdfdb1364c1db36fd14fd3f7ed2520aaf59`
- PHP文法: `CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4`、SHA-256: `3b090f1ebfaae841781c1ece2060b5b6a047655482d085f19eefa2dd2c5b2b6c`
- C#文法: `CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4`、SHA-256: `5514f7acbe7141110860e089b6b6e8ff910a5f863782869035f15d473fd3a0c2`

使用した文法はリポジトリ内の`CCFinderSW-1.0/grammarsv4`にあるものを優先した。既存のtoken dump実験を再利用し、今回、生成物保存、静的metadata抽出、ground-truth、分類、比較、metrics JSONを追加した。共通runtime JSON、skip観測、CCFX prep変換はまだ対象外である。

### 作業開始時の状態

作業開始時から`.gradle`配下に変更表示があり、実験ブランチ上の既存状態として扱った。今回、元`.g4`、CCFinderSW本体、生成Java原本は変更していない。

## C. 実装内容

実際に存在する実験コードは次の通りである。

- `experiments/antlr-java-lexer/run.sh`: ANTLR 4.7.1で指定`.g4`からJava Lexerを生成し、ANTLR runtimeをclasspathにして生成Javaと`TokenDump`を`javac`でコンパイルし、Lexerをreflectionでロードして実行する。
- `experiments/antlr-java-lexer/TokenDump.java`: `CharStreams`、`Lexer.nextToken()`、`Vocabulary`を使用し、token index、token type、symbolic name、channel、line、column、start/stop offset、textをタブ区切りで出力する。`literalName`、display name、mode、rule name、action、predicate、ATNは出力しない。
- `experiments/antlr-java-lexer/verify.sh`: Lua/PHP/C#の3文法と入力で`run.sh`を実行し、Luaの`COMMENT`、PHPの`HtmlStyleOpen`と`Comment`、C#の`INTERPOLATED_VERBATIUM_STRING_START`をgrepで確認する。
- `experiments/antlr-java-lexer/inputs/long-brackets.lua`: Luaのlong commentと`=`の数が異なるlong stringを含む。
- `experiments/antlr-java-lexer/inputs/mixed.php`: HTML、CSSを含む`<style>`、HTML終了、PHP開始、PHP行コメント、`echo "hello"`を含む。
- `experiments/antlr-java-lexer/inputs/strings.cs`: 通常の逐語文字列、補間逐語文字列、補間通常文字列を含む。
- `experiments/antlr-generated-lexer/generate.sh`: 3文法からJavaを生成し、同じANTLR runtimeでコンパイルする。生成物一覧、SHA-256、build manifestも保存する。
- `experiments/antlr-generated-lexer/MetadataExtractor.java`: compiled Lexerをreflectionでロードし、Vocabulary、rule/mode/channel API、ATNを使ってmetadata JSONを生成する。
- `experiments/antlr-generated-lexer/classify.py`: metadataと手作業ground-truthを比較し、7カテゴリ、根拠、confidence、TP/FP/FN、Precision/Recall/F1、UNKNOWN、誤分類一覧を出力する。
- `experiments/antlr-generated-lexer/verify.sh`: Phase 1〜4の生成、コンパイル、抽出、分類、JSON検証を一括実行する。
- `experiments/antlr-generated-lexer/README.md`: 再生成方法、保存方針、制約を説明する。

生成Javaは`experiments/antlr-generated-lexer/generated/{lua,php,csharp}`に保存され、classは`classes/`に生成される。metadata、ground-truth、classificationはそれぞれ同ディレクトリ配下のJSONとして保存される。

## D. 結果の要約

| 言語 | COMMENT | STRING | RESERVED | 静的情報だけで可能か | 動的実行が必要か | 言語別設定 | 言語専用コード |
|---|---|---|---|---|---|---|---|
| Lua | static TP=2, FP=0, FN=1, F1=0.800 | static TP=3, FP=0, FN=0, F1=1.000 | static TP=18, FP=4, FN=0, F1=0.900 | token/literal/rule/mode/channel名は取得可能、実行channelは未確定 | 範囲・skipはYES | literal/token名 | long bracket groupingは動的側 |
| PHP | static TP=3, FP=4, FN=0, F1=0.600 | static TP=1, FP=10, FN=5, F1=0.118 | static TP=5, FP=100, FN=0, F1=0.091 | mode/rule/channel名は取得可能、意味分類は設定不足 | mode/action/範囲はYES | token/mode/channel対応 | PHP mode/action、埋め込み言語 |
| C# | static TP=4, FP=0, FN=1, F1=0.889 | static TP=7, FP=3, FN=0, F1=0.824 | static TP=9, FP=101, FN=0, F1=0.151 | fixed literalとrule候補は取得可能、directive除外は要設定 | 補間全体とchannelはYES | token/mode/channel対応 | 補間・verbatim状態のgrouping |

分類精度は、元`.g4`を読んで別途作成したground-truthとの静的metadata比較である。動的token認識の精度ではない。JSONの`null`は分母0によるN/Aを表す。

| 言語 | カテゴリ | TP | FP | FN | Precision | Recall | F1 | UNKNOWN |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Lua | COMMENT | 2 | 0 | 1 | 1.000 | 0.667 | 0.800 | 0 |
| Lua | STRING | 3 | 0 | 0 | 1.000 | 1.000 | 1.000 | 0 |
| Lua | RESERVED | 18 | 4 | 0 | 0.818 | 1.000 | 0.900 | 0 |
| PHP | COMMENT | 3 | 4 | 0 | 0.429 | 1.000 | 0.600 | 0 |
| PHP | STRING | 1 | 10 | 5 | 0.091 | 0.167 | 0.118 | 0 |
| PHP | RESERVED | 5 | 100 | 0 | 0.048 | 1.000 | 0.091 | 0 |
| C# | COMMENT | 4 | 0 | 1 | 1.000 | 0.800 | 0.889 | 1 |
| C# | STRING | 7 | 3 | 0 | 0.700 | 1.000 | 0.824 | 1 |
| C# | RESERVED | 9 | 101 | 0 | 0.082 | 1.000 | 0.151 | 1 |

## E. 重要な実測結果

### Lua

入力は`--[=[ comment ]=]`と`[==[\ntext\n]==]`である。`verify.sh`の出力では次を得た。

```text
0  64  COMMENT     channel=1  start=0  stop=16  --[=[ comment ]=]
4  59  LONGSTRING  channel=0  start=28 stop=41  [==[\ntext\n]==]
```

ANTLRの`stop`はinclusiveであるため、exclusiveな終端に変換するには`stop + 1`が必要である。Luaのlong commentは`COMMENT` tokenとしてhidden channel 1に出力され、long stringは`LONGSTRING`の1 tokenである。これは開始と終了の`=`数を実行Lexerが対応付けた結果であり、正規表現復元は使っていない。

### PHP/HTML

入力では、HTML token、`HtmlStyleOpen`、CSS相当の全体を表す`StyleBody`、`PHPStart`、PHP tokenが順に出た。

```text
HtmlStyleOpen channel=0 start=7 stop=12
StyleBody     channel=0 start=14 stop=61
PHPStart      channel=4 start=63 stop=67
Comment       channel=2 start=71 stop=82 php comment
DoubleQuote   channel=0 start=89 stop=89
StringPart    channel=0 start=90 stop=94 hello
```

PHP文法はCSS内部を細かくtokenizeせず`StyleBody`へまとめる設計である。`Comment`はchannel 2、`SingleLineComment`はchannel 4として出た。modeの値そのものは`TokenDump`では取得していないが、`HtmlStyleOpen`、`PHPStart`、PHP専用tokenの遷移結果からmode依存の実行効果を観測した。PHPのheredoc/nowdocは今回の入力に含めていないため、終端識別子の実測は未完了である。

### C#

逐語文字列は1 tokenであり、内部の`// not comment`はコメントにならなかった。

```text
VERBATIUM_STRING channel=0 start=8 stop=38  @"a ""quote"" b // not comment"
INTERPOLATED_VERBATIUM_STRING_START channel=0 start=49 stop=51 $@"
VERBATIUM_INSIDE_STRING channel=0 start=52 stop=53 a 
VERBATIUM_INSIDE_STRING channel=0 start=67 stop=83 b // not comment
```

補間逐語文字列は開始、内容、引用符、式内の`IDENTIFIER`など複数tokenに分かれた。補間全体のSTRING範囲を作るグルーピング処理はまだ実装していない。通常の補間文字列でも`INTERPOLATED_REGULAR_STRING_START`と内部tokenが出ることは確認した。

### skip、位置、予約語

今回の`TokenDump`は`nextToken()`で得られるtokenだけを観測し、生成Lexerの`skip()`をoverrideしていない。そのため、`skip()`されたコメントを取得できたとは結論できない。channelへ出るコメントは取得できる。

`start`/`stop`、line、columnは生成Lexerの出力として得られ、代表入力でソース位置と一致した。ただし、共通runtime JSONへ変換してoffsetを自動比較するテストは未実装である。予約語は`Vocabulary`のfixed literalとsymbolic nameを静的分類し、ground-truthとPrecision/Recall比較を実施した。分類器はmetadata tokenのliteral、rule、modeを使うが、実行時channelやcontextual keywordまでは確定しない。

## F. 分かったことと未検証事項

### 静的に取得できる情報

`MetadataExtractor.java`で、生成Lexerのtoken type、symbolic token name、literal token name、display name、rule names、mode names、channel names、ATN rule/mode/state数を実際にJSON化した。`ATN.ruleToTokenType`でtoken/rule対応を取得し、`modeToStartState`からmodeを取得した。Luaではimplicit literal tokenのliteralと`T__n`対応、PHP/C#では複数modeとchannel名を確認できた。

### ATNから取得できると考えられる情報

`Lexer.getATN()`からrule数、mode数、state数、rule/token対応、mode配置を実際に取得した。transition種別、rule start/stop、action transition、predicate transitionの詳細走査はまだ実装していない。action、predicate、状態変数の意味をATNだけから完全に復元できるとは扱わない。

### Lexer実行が必要な情報

実際のコメント・文字列の範囲、channel、mode/action/state変数の効果、Luaのlong bracket境界、PHPのmode切替、C#補間文字列のtoken列は実行結果が必要である。`skip()`されたtokenは通常の`nextToken()`結果に現れないため、専用観測が必要である。

### 設定で補える情報

token名、comment channel、string token群、reserved token type/literalの対応は、今回のground-truthのような言語別設定で補える。実測metricsではPHPの名前だけの分類が低precisionだったため、mode、channel、directive除外を設定へ含める必要がある。ただし補間文字列の連結規則、PHPのmode/action、skip観測は設定だけでは不足する。

### 言語専用コードが必要な情報

PHPのHTML/PHP/CSS混在とmode/action、C#の補間・逐語文字列グループ化、skipコメントの確実な回収は言語専用Adapterまたは生成Lexerサブクラスが必要になる可能性がある。Luaのlong bracketはLexerが1 token化できるため、単純な分類だけなら設定で足りる可能性がある。

### 取得できなかった情報、今回未検証の情報

actionの正確なrule対応、semantic predicate、skip/more/type commandの完全な一覧、ATN transition分類、modeの現在値、recursive/cyclic ruleの判定、contextual keyword、`skip()`コメント、PHP heredoc/nowdoc、共通runtime JSON、CCFX prep変換は未取得または未検証である。rule/token対応、ground-truth比較、分類metrics JSONは実装済みである。

## G. CCFinderSWへの接続可能性

置き換え候補は、`.g4`解析から正規表現候補を作る`Aleesa`/`ANTLRInitializer`経路と、`CommentsRemoverUsingRegex`および`PreProcessEase.tokenizeANTLRAvoidStr`を直接置き換えるLexer Adapterである。本体統合は今回実施していない。

- COMMENT: channel tokenをコメント範囲として空白化する。skipコメントは別観測で範囲を記録する必要がある。
- STRING: 1 tokenならその範囲を保護する。複数tokenなら開始から終了までをグループ化し、必要なら内部の式をCCFinderSW仕様に合わせる。
- RESERVED: token typeと`Vocabulary`のliteral/symbolic nameを、既存の予約語判定へ対応付ける。固定literalを全て予約語にしてはならず、演算子・区切り記号・directiveを除外する。
- 識別子正規化: 既存CCFinderSWの識別子・Token/Pre処理は再利用候補だが、生成Lexerのtoken単位との差を確認してから接続する。
- token単位の差: 現行処理は英数字列をまとめ、記号を基本的に1文字ずつ分割する。生成Lexerは`StyleBody`、逐語文字列、補間内部など、文法固有のtoken単位を返すため、1対1対応とは限らない。

本体統合前に、offsetのexclusive化、skip観測、複数token文字列、reserved判定、hidden channelとコメントの区別、PHP/C#の専用設定、文法更新時の再生成固定を解決する必要がある。生成Lexerの出力を`Token`/`Pre`/CCFX prepへ変換できる見込みはあるが、今回まだ変換コードで実証していない。

## H. 結論（YES/PARTIAL/NO）

1. 生成Java LexerからCOMMENT情報を取得できる: **PARTIAL**。channelへ出るコメントは実測できたが、skipコメントの回収は未実装。
2. 生成Java LexerからSTRING情報を取得できる: **PARTIAL**。Lua/C#の文字列tokenとPHPの文字列ruleを静的分類できたが、複数tokenの共通範囲化は未実装。
3. 生成Java LexerからRESERVED情報を取得できる: **PARTIAL**。fixed literalとtoken typeを抽出・比較できたが、PHP/C#のmode/directive除外で設定が必要。
4. 正規表現を復元する必要はない: **YES**。代表例はLexer実行結果から直接得られた。
5. 3言語で共通コードを利用できる: **PARTIAL**。生成、コンパイル、Vocabulary、ATN metadata、分類metricsは共通化できるが、mode/action/補間の後処理は差がある。
6. 設定ファイルだけで新言語を追加できる: **NO**。単純token分類なら可能性はあるが、任意のaction、mode、base class、埋め込み言語を保証できない。
7. CCFinderSWの現行字句解析を置き換えられる見込みがある: **PARTIAL**。有望だが、CCFX prepまでの変換と既存挙動の互換性が未検証。
8. 次の段階としてCCFX prepへの変換実験へ進める: **YES**。Phase 1〜4のJSON成果物ができたため、次は共通runtime形式、skip、文字列グループ化、予約語設定を最小実装する。

## I. 制限・未解決事項

- 文法の正確な上流バージョンや更新履歴は固定していない。SHA-256は記録した。
- ANTLR tool/runtimeは4.7.1を使用した。一方、同梱生成物にはANTLR 4.5.1由来と見られる警告があり、tool/runtimeと文法生成時runtimeの完全一致は未確認である。
- Java targetのaction、base class、import、tokenVocab、補助ファイル依存は3文法すべてについて網羅確認していない。
- contextual keywordはParserや文脈を見なければ確定できない場合がある。
- skipコメントは`TokenDump`では取得していない。
- C#補間文字列の開始・内部・終了tokenを1つのSTRINGへまとめる規則は未実装である。
- PHP heredoc/nowdocは今回の入力に含めていない。PHP文法に状態保持用の`_heredocIdentifier`/`CheckHeredocEnd`があることは既存調査で確認されているが、終端の実測は未完了である。
- PHP文法のCSS部分は`StyleBody`という単一tokenであり、CSSコメントをPHP Lexerが独立COMMENTとして出すことは今回確認していない。これは文法設計上の境界であり、直ちに不具合とは言えない。
- 別言語へ一般化できるのは、まずLexerの直接実行、Vocabulary参照、offset付きtoken出力までである。action、mode、埋め込み、Parser依存の分類は一般化できない可能性がある。

## J. テスト結果

実行したコマンド:

```sh
sh experiments/antlr-generated-lexer/verify.sh
sh ./gradlew test
```

結果:

- `experiments/antlr-generated-lexer/verify.sh`: 成功。3言語の生成Java、コンパイル、metadata、ground-truth比較、classification metrics、JSON構造を確認した。
- `sh ./gradlew test`: `BUILD SUCCESSFUL`、1 actionable task、失敗0件。
- 自動テスト成功数: Phase 1〜4の言語別パイプライン3件、JSON構造検証3件、metricsカテゴリ検証3件。既存Gradle testは成功したが、`test`タスクは`NO-SOURCE`だった。
- 失敗数: 0
- スキップ数: 0。skipコメント観測やruntime JSONは未実装であり、スキップ扱いではなく未検証である。
- 既存CCFinderSWへの影響: 本体・元文法を変更していないため、今回の実験コードによる本体回帰は確認されていない。
- 注意: `./gradlew test`はwrapperの実行権限不足で失敗するため、`sh ./gradlew test`を使用した。

## K. 変更ファイル一覧

### 新規追加・既存実験

- `docs/antlr-java-lexer-feasibility-report.md`: 既存の実現可能性報告書。現行方式、生成Lexer実験、制約を説明する。
- `docs/antlr-generated-lexer-handoff-summary.md`: 本文書。別AIへ単独で渡せる実験結果と次の計画の要約。
- `experiments/antlr-java-lexer/TokenDump.java`: 生成Lexerのtoken観測プログラム。
- `experiments/antlr-java-lexer/run.sh`: 生成、コンパイル、実行スクリプト。
- `experiments/antlr-java-lexer/verify.sh`: 3言語の簡易自動検証。
- `experiments/antlr-java-lexer/inputs/long-brackets.lua`: Lua入力。
- `experiments/antlr-java-lexer/inputs/mixed.php`: PHP/HTML入力。
- `experiments/antlr-java-lexer/inputs/strings.cs`: C#入力。
- `experiments/antlr-generated-lexer/generate.sh`: Java Lexer生成、コンパイル、manifest作成。
- `experiments/antlr-generated-lexer/MetadataExtractor.java`: compiled Lexerからmetadata JSONを抽出。
- `experiments/antlr-generated-lexer/classify.py`: 7カテゴリ分類とground-truth比較。
- `experiments/antlr-generated-lexer/verify.sh`: Phase 1〜4自動検証。
- `experiments/antlr-generated-lexer/README.md`: 実験の再生成方法と制約。
- `experiments/antlr-generated-lexer/ground-truth/{lua,php,csharp}.json`: `.g4`を手作業で確認した正解データ。
- `experiments/antlr-generated-lexer/metadata/{lua,php,csharp}.json`: 実生成Lexerからのmetadata。
- `experiments/antlr-generated-lexer/classification/{lua,php,csharp}.json`: 分類、metrics、誤分類一覧。
- `experiments/antlr-generated-lexer/generated/{lua,php,csharp}/*.java`: 実際に生成・コンパイル確認したJava Lexer。

### 変更・自動生成・Git管理対象外

- 今回の追加・変更: `experiments/antlr-generated-lexer/`一式、`docs/antlr-java-lexer-feasibility-report.md`、`docs/antlr-generated-lexer-handoff-summary.md`。
- 元`.g4`、CCFinderSW本体、既存`experiments/antlr-java-lexer/`は変更なし。
- 生成Java: `experiments/antlr-generated-lexer/generated/`に保存済み。再生成可能なため、将来Git管理対象から外す場合もmanifestと`generate.sh`を残す。
- classファイル: `experiments/antlr-generated-lexer/classes/`。ビルド生成物としてGit管理対象外。
- 一時ログ: `/tmp`下の検証ログ。成果物ではない。
- 作業ツリーには作業開始時から`.gradle`配下の変更表示があった。これは今回の文書作成対象ではなく、削除・復元していない。

## L. 次に行うべき作業（優先順位）

1. **PHP/C#分類設定を精密化する**
   - 目的: 今回低かったPHP/C#のprecisionを、mode、channel、directive、埋め込み言語の設定で改善する。
   - 変更対象: `ground-truth/`と`classify.py`。
   - 成功条件: false positiveをrule単位で説明し、カテゴリ定義を固定したmetricsを再現する。
   - 想定障害: 同名rule、type/channel command、contextual keyword。
2. **ATN transition metadataを拡張する**
   - 目的: action、predicate、rule transition、循環候補の静的情報をJSONへ追加する。
   - 変更対象: `MetadataExtractor.java`。
   - 成功条件: transition種別と取得不能理由を3言語で出力する。
   - 想定障害: ATNだけではJava actionの意味や状態変数を復元できない。
3. **共通runtime JSONとskip観測を実装する**
   - 目的: CCFinderSWへ渡せるexclusive offsetの中間形式を作る。
   - 変更対象: lexer adapter、skip観測用サブクラスまたは実験用生成コピー、JSON writer。
   - 成功条件: 3言語でコメント・文字列・予約語候補の範囲、line/column、groupMembersを出力する。
   - 想定障害: skipは通常token streamに出ない、mode値の安全な観測、補間の終了判定。
4. **CCFX prep変換の小規模実験を行う**
   - 目的: 既存Regex経路を変更せず、1入力を生成Lexer結果からToken/Pre/CCFX prepへ変換する。
   - 変更対象: 実験用Adapterと比較スクリプト。
   - 成功条件: offset、コメント除去、文字列保護、予約語/識別子分類が既存出力と比較できる。
   - 想定障害: 生成Lexerのtoken単位と既存記号分割の差、hidden channel、CCFinderSWの既存形式制約。
5. **PHP heredoc/nowdocとJava依存を追加検証する**
   - 目的: 状態依存規則と生成環境依存の境界を確定する。
   - 変更対象: PHP/C#の追加入力、base class/import確認、tool/runtime固定情報。
   - 成功条件: heredoc/nowdoc、補間、action、依存クラスの成功・失敗を記録する。
   - 想定障害: 文法のJava target依存、補助クラス不足、Parserなしでは分類できない文脈。

## 最終判断

生成Java Lexerから、少なくともchannelへ出るコメント、Luaのlong string、C#の逐語・補間文字列のtoken列、PHPのHTML/PHP境界を実測取得できた。さらに今回、生成Java保存、compiled Lexer APIによるmetadata JSON、手作業ground-truth、7カテゴリ分類、比較metrics JSONを実装・実行した。正規表現を復元する必要はない。

従って、次の実験は本体統合ではなく、今回の低precision分類を言語別設定で精密化し、ATN transition、skipコメント、複数token文字列を含む共通runtime JSONを実装するべきである。