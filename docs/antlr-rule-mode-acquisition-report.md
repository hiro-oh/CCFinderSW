# ANTLR規則・mode情報取得方法の実験報告

## A. 今回の目的と成功条件

目的は、CCFinderSWでコメント・文字列・予約語の字句規則を利用する前提として、対象規則を人がseedとして与えた後に、`.g4` と生成Java Lexerのどちらから何を取得でき、両者をどう対応付けられるかを確認することである。完全なANTLRパーサ、全入力での一致、必要集合の最小性、CCFX prep統合、大規模性能評価は対象外とした。

成功条件は、(1) 規則本体・参照閉包・fragment・command・action/state候補・順序を取得できる、(2) modeの入口・構造的経路・復帰の限界を明示できる、(3) Vocabulary/ATN metadataとsource ruleを対応付けられる、(4) PHPの代表入力で静的候補と実行時modeを比較できる、(5) LuaとC#で同じ方法の成立範囲を確認できる、である。

## B. 既存成果と今回追加した作業

### 過去の報告・既存コード

既存の[ANTLR依存追跡実験](../experiments/antlr-dependency-analysis/)は、軽量なテキスト抽出で起点規則の参照閉包、command、mode候補、action識別子候補を取得していた。既存の[生成Lexer実験](../experiments/antlr-generated-lexer/)は、ANTLR 4.7.1でLua/PHP/C# Lexerを生成・コンパイルし、`ruleNames`、`modeNames`、`channelNames`、`Vocabulary`、`ATN.ruleToTokenType`をJSON化していた。これらの報告は、今回開始前から存在した成果として扱った。

### 今回追加したもの

今回追加した[取得スクリプト](../experiments/antlr-rule-mode-acquisition/acquire.py)は、依存分析JSONと生成Lexer metadata JSONを統合する。具体的には、target modeへの incoming rule、modeグラフの逆向き閉包、DEFAULT_MODEからの構造的到達可能性、関連mode内の規則順序、source ruleと生成token/modeの対応を出力する。

追加した[TraceLexer.java](../experiments/antlr-rule-mode-acquisition/TraceLexer.java)は、`nextToken()`呼び出しごとに、呼び出し前mode、呼び出し後mode、token type/name、channel、入力範囲、textを観測する。追加したPHP fixtureはHTMLからPHP、コメント内PHP終端、script内PHPと文字列中のコメント記号を分けて確認する。

既存の`analyze.py`と`MetadataExtractor.java`自体は変更していない。今回の検証は[run.sh](../experiments/antlr-rule-mode-acquisition/run.sh)から既存実験を再実行した後に行う。

## C. 環境・対象文法・対象規則

- OS: Linux
- Java: `java -version` は生成時に`experiments/antlr-generated-lexer/generated/build-manifest.txt`へ記録
- ANTLR tool/runtime: `lib/antlr-4.7.1-complete.jar`, version 4.7.1
- PHP: `CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4`
- Lua: `CCFinderSW-1.0/grammarsv4/lua/Lua.g4`
- C#: `CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4`

主対象PHPのseedは`MultiLineComment`、`SingleLineComment`、`ShellStyleComment`、`Comment`、`PHPEndSingleLineComment`、`CommentQuestionMark`、`CommentEnd`である。比較対象はLuaの`COMMENT`、`LINE_COMMENT`、`NESTED_STR`、C#の4種の通常/doc commentである。カテゴリの完全自動発見は試していない。

## D. `.g4`からの取得方法と結果

### 取得方法

既存の軽量抽出器は、lexer ruleの大文字名、`fragment`、mode境界、source span、規則本文を行単位で保持する。本文中の既知rule名を参照辺とし、seedから推移閉包を求める。`pushMode`、`mode`、`popMode`、`channel`、`type`、`skip`、`more`をcommandとして抽出し、波括弧を含むruleからaction/predicate候補、action内の未知識別子を補助状態・メソッド候補として記録する。各ruleの入力順を保存する。

### PHPの代表結果

PHPの単一行コメント部分は次のようになっている（source spanは今回の実ファイルの行番号）。

```antlr
mode PHP;
SingleLineComment:  '//' -> channel(SkipChannel), pushMode(SingleLineCommentMode);
ShellStyleComment:  '#'  -> channel(SkipChannel), pushMode(SingleLineCommentMode);

mode SingleLineCommentMode;
Comment:             ~[\r\n?]+ -> channel(PhpComments);
PHPEndSingleLineComment: '?' '>';
CommentQuestionMark: '?' -> type(Comment), channel(PhpComments);
CommentEnd: [\r\n] -> channel(SkipChannel), popMode;
```

`.g4`から取れた主情報は次の通りである。

- `SingleLineComment`と`ShellStyleComment`がPHP modeから対象modeへ入る入口候補。
- 対象mode内のruleは8個。seedからの直接参照閉包は、`Comment`、`CommentEnd`、`CommentQuestionMark`、`PHPEndSingleLineComment`、`SingleLineComment`、`ShellStyleComment`、および同じseedの`HtmlComment`、`MultiLineComment`である。comment ruleの内部はfragment参照をほぼ持たない。
- `SingleLineComment`は`channel(SkipChannel)`と`pushMode(SingleLineCommentMode)`、`CommentEnd`は`channel(SkipChannel)`と`popMode`を持つ。
- PHP sourceには`@lexer::members`の`nextToken()` override、`AspTags`、`_scriptTag`、`_styleTag`、`_heredocIdentifier`、`_prevTokenType`、`_htmlNameText`、`_phpScript`、`_insideString`、`CheckHeredocEnd`がある。これらはrule参照閉包の外側にあるが、mode復帰、script、heredoc、合成semicolonに関係する。
- PHP全体のmodeは`DEFAULT_MODE`、`XML`、`INSIDE`、`HtmlQuoteStringMode`、`HtmlDoubleQuoteStringMode`、`SCRIPT`、`STYLE`、`PHP`、`InterpolationString`、`SingleLineCommentMode`、`HereDoc`である。

`analyze.py`は意図的に完全なANTLR文法ではない。文字列リテラル内の識別子、action内の識別子、コメント、複数行の特殊な記法は誤抽出の余地がある。今回のPHPコメント例では、参照先がないcomment ruleのため参照辺の誤抽出は確認されなかったが、action/stateはsource inspectionなしに意味付けできない。よってsource spanと本文を保持し、未知のaction/predicateを不明として残す設計にした。

## E. 生成Lexer／ATNからの取得方法と結果

生成Lexerは既存の`generate.sh`でANTLR 4.7.1から生成・コンパイルした。metadata extractorは次を取得する。

- `ruleNames`、`modeNames`、`channelNames`
- `Vocabulary.getLiteralName()`、`getSymbolicName()`、`getDisplayName()`
- `ATN.ruleToTokenType`
- `ATN.modeToStartState`からmodeとrule startの対応
- rule数、mode数、ATN state数

PHPでは`SingleLineComment`はtoken type 41、`Comment`は227、`CommentEnd`は229、`PHPEndSingleLineComment`は228、`MultiLineComment`は40であり、生成metadata上のmodeもsourceの所属modeと一致した。`CommentQuestionMark`は`type(Comment)`でtoken typeを別ruleへ移すため、独立したtoken mappingを持たなかった。これは、生成Lexer側のtoken mappingだけを見てruleを復元する際の具体的な欠損例である。

ATNは実行構造、rule start、token type、mode開始状態の取得には有用である。しかし、ATNからJava actionの意味、`nextToken()` override、helper methodの意味、コメントchannelがCCFinderSW上で何を意味するかは復元できない。`skip`、`more`、`type`の最終的なtoken列への影響も、実行またはsource確認が必要である。

## F. 両者の対応付け

| 情報 | `.g4` | 生成Lexer/ATN | 取得しやすい側 | 今回の対応 |
|---|---|---|---|---|
| rule本体・source span | 本文・行番号 | 通常は正規表現構造のみ | `.g4` | source ruleを保持 |
| fragment・参照閉包 | 明示的な名前と本文 | ATN transitionで構造は見える | `.g4`が説明しやすい | `references`で閉包 |
| rule順序 | source順 | ATN/ruleNames順 | 両方 | source indexを保存 |
| mode所属 | `mode`境界 | `modeToStartState` | 両方 | source modeとATN modeを比較 |
| token type/name | commandやrule名から推定 | Vocabulary/`ruleToTokenType` | 生成Lexer | token mappingを付加 |
| command | `-> pushMode`, `popMode`, `channel`等 | 実行結果・ATN action | `.g4`の記述、意味は実行 | source commandを保持 |
| action/predicate本文 | Java sourceとして読める | ATN action indexは得られる | `.g4`/生成Java | action識別子候補と限界を記録 |
| helper/state | `@lexer::members` | 生成Javaのmembers/override | sourceと生成Java | PHPのmembersを別途確認 |
| 最長一致・競合 | rule順とliteralを見られる | ATNが実行選択を表す | 実行Lexer | 関連modeの順序を記録 |
| mode stack復帰 | `popMode`記述 | runtime stackの実値 | runtime実行 | tracerで前後modeを観測 |

自動対応付けは、rule名が`ATN.ruleToTokenType`のtoken mappingを持つ場合はできた。fragment、`type(...)`で別tokenになるrule、暗黙literal tokenについては対応が一対一でないかmappingがない。したがって、rule名・source spanを主キーにし、token type mappingは補助情報として扱うのが安全である。

## G. 対象規則へ到達するmode経路の取得

取得処理は二段階である。

1. すべてのruleの`pushMode(X)`/`mode(X)`から、`fromMode -> X`の構造辺を作る。target modeについて逆向きに入口modeを集める。
2. DEFAULT_MODEからforward reachabilityを計算し、逆向き候補だけでなく初期modeから構造的に到達可能かを確認する。

PHPの`SingleLineCommentMode`について、入口ruleは`PHP` modeの`SingleLineComment`と`ShellStyleComment`である。DEFAULT_MODEからの構造的到達可能性は真で、forward closureにはPHP、INSIDE、SCRIPT、InterpolationStringなどが含まれた。逆向きclosureも複数modeに広がった。これはPHPがHTML、script、quote、interpolationからPHPへ入れるためであり、seedの参照閉包だけから得たmode集合ではない。

`pushMode`と`mode`は別kindで記録した。`pushMode`はstackへ現在modeを保存して遷移し、`mode`は現在modeを置換する。`popMode`の遷移先は「caller/previous mode」としか静的には表せず、具体的復帰先はstack状態に依存する。action内の`popMode()`や`pushMode()`、Java `nextToken()`内のmode操作は軽量command抽出だけでは入口辺にならない。このためmode graphのpathは「構造上の候補」であり、入力で実行可能なpathとは区別した。

## H. 代表入力による観測結果

### H-1 HTMLからPHP、通常のline/block comment

入力（[`php-html-comment.php`](../experiments/antlr-rule-mode-acquisition/fixtures/php-html-comment.php)）:

```text
<html><?php
// line comment
$x = 1; /* block comment */
?>
tail
```

主要なtrace（`before`/`after`は一回の`nextToken()`前後）:

```text
DEFAULT_MODE -> INSIDE  HtmlOpen
INSIDE -> DEFAULT_MODE  HtmlClose
DEFAULT_MODE -> PHP  PHPStart
PHP -> SingleLineCommentMode  SingleLineComment, text=//
SingleLineCommentMode -> SingleLineCommentMode  Comment, channel=PhpComments
SingleLineCommentMode -> PHP  CommentEnd, text=\n
PHP -> PHP  MultiLineComment, channel=PhpComments
PHP -> DEFAULT_MODE  generated SemiColon, text=<null>
```

これはsource上の入口`SingleLineComment`、対象modeの`Comment`/`CommentEnd`、`popMode`の実行と対応する。block commentはPHP modeを離れず、`channel(PhpComments)`だけである。`?>`後のsemicolonはsource ruleだけでなく、`nextToken()` overrideの`PHPEnd`処理による。

### H-2 コメント内PHP終端

入力（[`php-end-in-comment.php`](../experiments/antlr-rule-mode-acquisition/fixtures/php-end-in-comment.php)）:

```text
<?php echo "Hello" // comment ?>
```

traceでは`//`でSingleLineCommentModeへ入り、`Comment`が` comment `を取り、次のtoken呼出し後に`before=SingleLineCommentMode`、`after=DEFAULT_MODE`、`name=SemiColon`、`text=<null>`となった。sourceには`PHPEndSingleLineComment: '?' '>';`があるが、実際のPHP終了処理とsemicolon合成は、生成Lexerの`nextToken()` overrideが`token.getType()`を検査してmodeを二度popし、必要なら新しい`CommonToken(SemiColon)`を返す処理による。これは、commandとrule本体だけでは不十分であることを直接示す。

### H-3 文字列中のコメント記号とscript内PHP

入力（[`php-embedded-string.php`](../experiments/antlr-rule-mode-acquisition/fixtures/php-embedded-string.php)）:

```text
<script><?php echo "/* not a comment */"; ?></script>
```

traceは`DEFAULT_MODE -> INSIDE -> SCRIPT -> PHP`と進み、`"`で`InterpolationString`へ入り、`/* not a comment */`全体を`StringPart`として出力し、comment tokenにはしなかった。その後`PHP -> SCRIPT -> DEFAULT_MODE`と復帰した。これは文字列内のcomment記号をrule nameやliteralだけで分類してはいけないこと、modeと実行Lexerが必要なことを示す。

観測上、mode前後とtoken列は直接観測した。一方「特定のATN transitionが選択された」ことはこのtracerだけでは直接観測しておらず、rule名との対応はsource確認とtoken metadataによる推定である。

## I. Lua・C#への適用結果

### Lua

seedを`COMMENT`、`LINE_COMMENT`、`NESTED_STR`として同じ手順を実行した。`COMMENT`と`LINE_COMMENT`はDEFAULT_MODEのtoken mappingを持ち、`NESTED_STR`はfragment/補助構造としてtoken mappingを持たなかった。`NESTED_STR`は次のような自己参照を持つ。

```antlr
NESTED_STR
    : '=' NESTED_STR '='
    | /* closing bracket alternatives */
```

この自己参照は閉包計算で循環として扱う必要があり、今回の探索はvisited setで停止した。`=`の数を対応させるlong bracket構造と、Lua commentのnestingを同じものとして扱ってはいない。LuaはPHPのような複数mode入口の主例ではなく、fragment/再帰構造の比較例として有効だった。

### C#

C#のcomment ruleはDEFAULT_MODEにあり、`COMMENTS_CHANNEL`へ送る。文字列・補間文字列は別に`INTERPOLATION_STRING`、`INTERPOLATION_FORMAT`等を使い、`INTERPOLATED_REGULAR_STRING_START`と`INTERPOLATED_VERBATIUM_STRING_START`が`pushMode(INTERPOLATION_STRING)`する。したがって、comment seedの参照閉包だけに文字列ruleは入らないが、文字列とcommentの競合・状態を調べる目的で、mode入口とstring関連ruleを別の追加seed/関連modeとして取得した。

C#ではactionが`interpolatedStringLevel`、`interpolatedVerbatiums`、`verbatium`、`curlyLevels`を変更する。これはsource actionと生成Java/runtimeを確認する根拠であり、comment ruleの参照辺から自動的に得たものではない。今回の統合JSONでは`INTERPOLATION_STRING`をtarget modeとして、DEFAULT_MODEからの構造経路と入口ruleを記録した。補間文字列の全意味を静的に解釈したわけではない。

## J. 不足・過剰候補・人手補完

不足した情報は、Java actionの意味、base classの処理、`nextToken()` override、mode stackの具体値、最長一致の実行選択、`type()`による最終token対応、入力制約付きpredicateの真偽である。PHPでは`@lexer::members`と生成Javaのoverrideを人手確認した。C#ではaction/state名を人手で文字列関連modeに追加した。Luaでは`NESTED_STR`を明示seedした。

過剰候補は、PHPのmode逆向きclosureに現れるHTML quote、SCRIPT、InterpolationString等である。これらは対象comment modeへの構造上の到達経路候補としては必要だが、すべての入力で実行されるわけではない。絞り込みには、入口ruleのliteral、入力コンテキスト、実行trace、channel/type、actionの条件を使える。

`CommentQuestionMark`のように独立token typeを持たないrule、fragment、implicit literalは生成metadataからの逆算で漏れうる。rule orderは記録したが、同じmode内の最長一致・rule優先順位を静的順序だけで意味付けしていない。

## K. 方法の成立範囲と限界

今回の事実から、次の組み合わせは実用上試す価値がある。

1. 人手または設定でCOMMENT/STRING/RESERVEDのseedを与える。
2. `.g4`からsource span、rule本体、fragment/参照閉包、command、action/predicate候補、rule順を取得する。
3. 全ruleのmode commandから入口の逆向き候補とDEFAULT_MODEからの構造的到達可能性を取得する。
4. 生成LexerのVocabulary/ATNでtoken type、mode開始、rule mappingを補強する。
5. action、mode stack、`more`/`skip`/`type`、特殊終了処理が必要な例は、元のLexer全体を実行して補う。

この方法で、単純な同mode comment、fragmentを含む構成、明示的`pushMode`/`popMode`、token/channel分類のおおむねの取得は扱えそうである。PHPの単一行commentの入口・復帰はその範囲に入った。

反対に、参照閉包だけではmode入口が得られない。mode graphだけでは実行可能性・stack復帰・action条件が分からない。生成Lexer/ATNだけではsource span、Java actionの意味、分類意図が分からない。したがって「`.g4`か生成Lexerの一方だけで十分」「Lexer全体の実行が常に必須」とは今回の結果から断定できない。

## L. 未実装・未検証

- 公式ANTLR ParseTreeとの全rule/span自動照合。
- ATN transition/action indexをruleごとに詳細出力する処理。
- Java actionの一般的な静的解析。
- 全PHP heredoc/nowdoc、全HTML/script/style組合せのpath探索。
- C#補間文字列の実行traceとcommentの全競合例。
- Lua long bracketの全`=`数とcomment nestingの実行比較。
- 予約語のcontextual keyword判定の自動化。
- CCFinderSW tokenizationへの統合、CCFX prep変換、性能測定。

## M. 再現コマンド・変更ファイル

リポジトリrootから次を実行する。

```sh
sh experiments/antlr-rule-mode-acquisition/run.sh
```

このscriptは既存の生成Lexer検証を再実行し、`.g4`分析、source/ATN統合JSON、PHP runtime traceを`experiments/antlr-rule-mode-acquisition/results/`に生成する。主要な追加ファイルは次の通り。

- `experiments/antlr-rule-mode-acquisition/acquire.py`
- `experiments/antlr-rule-mode-acquisition/TraceLexer.java`
- `experiments/antlr-rule-mode-acquisition/run.sh`
- `experiments/antlr-rule-mode-acquisition/fixtures/*.php`
- `experiments/antlr-rule-mode-acquisition/README.md`
- `docs/antlr-rule-mode-acquisition-report.md`

生成元文法、生成Java、metadata、既存実験の成果は`experiments/antlr-generated-lexer/`にあり、scriptがANTLR jarとコマンドを再利用する。元文法は改変していない。

## N. 次に行う検証の提案

次は、PHPについて公式ANTLR parserまたはANTLR内部のATN情報とsource ruleのspanを照合し、誤抽出を定量化するのがよい。ただし完全パーサ開発を目的にせず、今回の代表ruleに限定する。続いて、Java生成Lexerにinstrumentationを入れてrule start/action/transitionを直接記録し、`nextToken()` override由来のmode変更と通常ATN由来の変更を分ける。最後に、C#補間とLua long bracketの小さなfixtureを追加し、静的mode候補・実行経路・token分類の差を比較する。

## 最終結論

- `.g4`からは、seedを起点にrule本文、source span、fragment/参照閉包、command、action/state候補、rule順を取る方法が使えそうである。
- 生成Lexerからは、Vocabulary、token type/name、modeNames、channelNames、ATNのrule/mode/state対応を取る方法が使えそうである。
- 両者を組み合わせる利点は明確だった。source側は意図・span・actionを説明し、生成側はtoken type/mode metadataを補った。`CommentQuestionMark`のように一対一対応しない例も露出した。
- 対象ruleへの到達経路は、mode graph上の入口候補とDEFAULT_MODEからの構造的reachabilityまで取得できた。実際の入力で選ばれるpathの証明ではない。
- mode遷移は明示的commandなら追跡でき、`popMode`は「stack上の復帰」としてしか静的に表せない。PHPの特殊な復帰とsemicolon合成はLexer全体の`nextToken()`実行が必要だった。
- Lexer全体を使ったのは、実行時のmode前後、token/channel、mode stack依存の復帰、action/overrideの意味を観測する部分である。静的取得JSONそのものはLexer実行結果ではない。
- 完全一致を前提にしなくても、seed設定 + `.g4`構造取得 + 生成Lexer metadata + 必要な例だけの全Lexer実行、という段階的方式は試す価値がある。
- それを支える事実は、PHPの`SingleLineComment`入口と`CommentEnd`復帰が静的候補とruntime traceで一致したこと、block commentと文字列中comment記号が異なるmode/tokenになったこと、Luaの再帰fragmentとC#のaction/stateが別の不足を示したことである。一般文法での完全性、最小性、性能、およびすべてのaction意味の取得は未検証の仮説として残る。
