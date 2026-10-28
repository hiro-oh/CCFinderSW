# ANTLR字句規則の起点特定・依存追跡の実現可能性検証

## A. 今回検証した問い

コメント・文字列・予約語を使うために、ANTLR文法から意味のあるLexer規則を特定し、起点規則から参照先を再帰的に集めれば十分な情報が得られるかを検証した。目的はCCFinderSWの統合ではなく、取得方法の成立条件と限界を実験で明らかにすることである。

結論を先に述べると、**規則参照の閉包は、文法内の構成要素（fragmentを含む）を取得する方法としては成立する。しかし、コメント・文字列・予約語を正しく利用するための十分な依存集合ではない**。modeの入口・出口、action/predicate、状態変数、`more`/`type`、最長一致と規則順序、文字列内のコメント記号、予約語と識別子の競合は、参照辺だけでは得られない。これらが必要な文法では、modeグラフ、生成Lexer/ATN、action・base class、またはLexer実行を追加で調べる必要がある。

## B. 既存方式と既存成果

### 現行CCFinderSWの確認

コードを確認した範囲では、`-antlr`経路は対象言語の生成Lexerを実行しない。`OptionReader`から`Aleesa.getGrammar`へ進み、同梱のANTLR文法用 `ANTLRv4Lexer`/`ANTLRv4Parser` で `.g4` を解析する。`Aleesa` は lexer rule、fragment、リテラル、rule参照を集め、`buildExpressionToSkip` で参照を再帰展開する。その後、`->skip`、`channel(HIDDEN)`、名前に `comment` を含む規則などをRegexへ結合し、`CommentsRemoverUsingRegex` と `PreProcessEase` に渡す。

したがって現行方式が保持する主情報は、規則名・一部のリテラル・Regex化された規則本体・簡略化された予約語候補である。mode、mode stack、action/predicate、状態変数、`more`、`type`、Lexerの優先順位は実行されず、正規表現化によって状態が失われる。

### 既存報告との区別

既存の [antlr-java-lexer-feasibility-report.md](/media/workspace/master/CCFinderSW/docs/antlr-java-lexer-feasibility-report.md) と [antlr-generated-lexer-handoff-summary.md](/media/workspace/master/CCFinderSW/docs/antlr-generated-lexer-handoff-summary.md) は、ANTLR 4.7.1でLua・PHP・C#の生成Java Lexerを生成・コンパイル・実行し、Vocabulary/ATN metadataと分類を抽出した前回成果である。そこでは、Lua long bracket、PHPのmode/channel、C#のverbatim/interpolated stringが生成Lexerで扱えることを確認している。

今回の再実行はそれとは分け、`experiments/antlr-dependency-analysis/run.sh` により**元の `.g4` の構造を解析する依存追跡実験**を実施した。今回追加した実験は元文法・生成Java・CCFinderSW本体を変更しない。

## C. 抽出方法の仮説・成立条件

### 仮説1: 起点を名前・channel・commandから自動特定できる

成立条件は、文法作者が意味を規則名に明示し、コメント規則が安定したchannel名を使い、文字列・予約語の規則にも命名規約があることである。これは補助的な候補生成には使えるが、意味の証明にはならない。

- `COMMENT` という名前がない場合がある。
- `channel(HIDDEN)` は空白・改行にも使われ、コメントを意味しない。
- `skip` は空白にも使われる。
- 固定アルファベットリテラルは予約語候補だが、文脈依存語や組込み名かどうかは文法だけでは確定しない。
- 最終的なカテゴリの起点は、少なくとも人の指定、言語設定、または検証済みの分類規則が必要である。

### 仮説2: 起点規則の識別子参照を再帰追跡すれば十分である

これは fragmentや他のLexer ruleを構造的に集める方法としては成立する。参照先が同一ファイル内のLexer ruleであり、`import`/`tokenVocab`/delegate grammarが解決済みなら、直接参照の推移閉包を機械的に得られる。

しかし、十分性の仮説は成立しない。参照されない規則が現在modeへの入口・出口を作ること、actionが状態を更新すること、別規則が同じ入力に対して競合することがあるためである。

### 仮説3: 追加の依存辺をmode/state/競合として分離すれば必要範囲を説明できる

今回採用した方法である。依存を一つの集合に潰さず、以下を別々に出力する。

1. `rule-reference`: rule本体の識別子参照。
2. `mode-transition`: `pushMode`、`mode`、`popMode`。
3. `action-or-predicate`: `{...}`、`{...}?` とそこに現れる状態・補助名。
4. `classification-evidence`: channel、skip、literal、命名など、意味分類の根拠。
5. `competition`: 同じ入力を消費し得る識別子・予約語・文字列規則。今回の実装では候補を出し、最長一致・規則順序の意味は未解釈として残す。

## D. 実装した取得・依存追跡の手順

[analyze.py](/media/workspace/master/CCFinderSW/experiments/antlr-dependency-analysis/analyze.py) は完全なANTLRコンパイラではなく、検証用の保守的な構造抽出器である。次の順で処理する。

1. `mode NAME;` と通常のLexer rule、`fragment` ruleを source span 付きで分離する。規則名と `:` が別行のLua記法にも対応した。
2. 各rule本体から、既知のrule名に一致する識別子を `references` として抽出する。
3. `pushMode`、`mode`、`popMode`、`channel`、`type`、`skip`、`more` をcommandとして記録する。
4. `{...}` を含むruleをaction/predicate候補として記録し、closure到達ruleのaction識別子を状態・helper候補として出す。
5. 明示されたseed、rule名に `comment` を含む候補、`COMMENTS_CHANNEL`/`PhpComments` を使う候補を分けて記録する。`HIDDEN`だけではコメント候補にしない。
6. seedからrule-referenceの推移閉包を計算する。
7. mode遷移を別グラフとして出し、seed modeと接続するmodeを出す。これは「必要集合」ではなく、過剰取得の可能性を示す診断結果である。
8. JSONに、取得理由、依存種別、判断不能項目、人手seedを保存する。

実行結果は [results/](/media/workspace/master/CCFinderSW/experiments/antlr-dependency-analysis/results/) に保存される。今回のスクリプトの構文抽出は、ANTLR toolで再コンパイルせず、元文法テキストからの依存関係説明に焦点を置いている。

## E. 対象文法の代表的な記述

### Lua

`Lua.g4` の `LONGSTRING` は次の構造である。

```antlr
LONGSTRING : '[' NESTED_STR ']' ;
fragment NESTED_STR
    : '=' NESTED_STR '='
    | '[' .*? ']'
    ;
COMMENT : '--[' NESTED_STR ']' -> channel(HIDDEN) ;
LINE_COMMENT : '--' (...) ('\r\n'|'\r'|'\n'|EOF)
              -> channel(HIDDEN) ;
```

コメントの起点から `NESTED_STR` は直接参照される。これはコメント境界の一部を得るために必要である。ただし、Luaの `NESTED_STR` は `=` の数を対応させる構造であり、コメントのネストとは説明しない。

### PHP

`PhpLexer.g4` ではコメントがPHP modeにあり、次のように mode依存である。

```antlr
mode PHP;
MultiLineComment: '/*' .*? '*/' -> channel(PhpComments);
SingleLineComment: '//' -> channel(SkipChannel), pushMode(SingleLineCommentMode);

mode SingleLineCommentMode;
Comment: ~[\r\n?]+ -> channel(PhpComments);
CommentEnd: [\r\n] -> channel(SkipChannel), popMode;
```

PHP modeへの入口は、コメント規則から参照されない `PHPStart` 等の別ruleにある。さらにHTML/Script/Style/Interpolation等のmodeからPHPへ入る入口が複数ある。

### C#

`CSharpLexer.g4` のコメントはDEFAULT_MODEのchannel規則である。

```antlr
SINGLE_LINE_COMMENT: '//' InputCharacter* -> channel(COMMENTS_CHANNEL);
DELIMITED_COMMENT: '/*' .*? '*/' -> channel(COMMENTS_CHANNEL);
```

一方、文字列は状態を使う。

```antlr
INTERPOLATED_REGULAR_STRING_START: '$"'
  { interpolatedStringLevel++; ... } -> pushMode(INTERPOLATION_STRING);
OPEN_BRACE_INSIDE: '{' { curlyLevels.push(1); } -> skip, pushMode(DEFAULT_MODE);
DOUBLE_QUOTE_INSIDE: '"' { ... } -> popMode;
```

コメント起点の直接参照から、補間文字列の状態変数やmode入口は得られない。しかしコメント記号を文字列内で誤認しないためには、実際のLexer状態と文字列境界が必要である。

## F. 実際の抽出結果と各項目を含めた理由

`run.sh` の実行結果は以下の通りである。

| 文法 | 起点 | 直接参照closure | mode診断 | 主な状態/不足 |
|---|---|---|---|---|
| Lua | 明示 `COMMENT`, `LINE_COMMENT`。channelは補助根拠 | `COMMENT`, `LINE_COMMENT`, `NESTED_STR` | DEFAULT_MODE | `NESTED_STR`を取得。再帰・modeなし |
| PHP | 明示したPHP/SingleLineCommentMode規則と、コメント名/channel候補 | 8規則。`MultiLineComment`からfragment参照なし | 11 mode候補へ広がる | PHP入口、mode stack、actionはclosure外 |
| C# | 明示4コメント規則と`DIRECTIVE_SINGLE_LINE_COMMENT` | 6規則。`InputCharacter`を含む | 5 mode候補へ広がる | コメント規則自体は単純だが文字列・directive競合は別 |

各項目を含めた理由は次の通りである。

- `NESTED_STR`: `COMMENT`本体の識別子参照が根拠。Luaコメントの終了境界に必要。
- `SingleLineCommentMode`内の `Comment`/`CommentEnd`: PHPのコメント継続・終了を表す規則であり、`pushMode`/`popMode` commandが根拠。
- PHPの `PHP`、`INSIDE`、`SCRIPT` 等: コメント起点からのrule参照ではなく、mode遷移commandの根拠。したがって「直接参照で取れたもの」とは別扱い。
- C#の `InputCharacter`: `SINGLE_LINE_COMMENT`本体の直接参照。`INTERPOLATION_STRING`や`curlyLevels`はコメントclosureには含まれず、文字列の正しい利用を検討した際に別途必要になる。
- `HIDDEN`、`skip`: 空白にも使われるため、単独ではコメント根拠に含めなかった。これは既存 `Aleesa.combineRegexToSkip` の単純判定より厳しい扱いである。
- 固定リテラル: 予約語候補として列挙できるが、予約語だと確定したものではない。C#の`IDENTIFIER: '@'? IdentifierOrKeyword`のような競合には規則順序と仕様解釈が必要である。

## G. 必要情報との照合、反例、不足・過剰取得

### 直接参照で取れたもの

- Luaのコメント境界に必要な `NESTED_STR`。
- C#のコメント本体に必要な `InputCharacter`。
- rule本体中のliteral、fragment、channel、command、source span。
- 文法ファイル内のmode一覧と、明示されたmode command。

### 別の方法が必要なもの

- PHPコメントを認識可能にするPHP modeへの入口。`PHPStart`等はコメントruleから参照されない。
- PHPのSingleLineComment終了、`PHPEndSingleLineComment`、外側PHP modeからの復帰。これはmode stackとcommandの解析が必要。
- PHPの`_heredocIdentifier`、`CheckHeredocEnd`、`nextToken()`によるtoken差替え・channel変更。rule参照グラフには現れないJava処理である。
- C#補間文字列の `interpolatedStringLevel`、`verbatium`、`curlyLevels`。actionとhelper/stateの解析または生成Lexer実行が必要。
- 文字列内の `//` や `/*` をコメントにしないこと。コメントruleだけでなく文字列mode・最長一致・現在状態が必要。
- 予約語と識別子の競合。固定literalを列挙するだけでは、`IDENTIFIER`との優先順位・`@`付き識別子・文脈依存性を判断できない。

### 反例と過剰取得

1. **名前反例**: 文法はコメント規則名を保証しない。PHPには `HtmlComment` や `Comment` があるが、実際の意味・channelは異なる。
2. **channel反例**: Lua/PHP/C#でHIDDENまたはSkipChannelは空白や構文制御にも使われる。channelだけでコメントを決められない。
3. **参照不足反例**: PHPのコメントはPHP mode内だが、PHP mode入口はコメントruleの参照先ではない。
4. **状態反例**: C#の補間文字列はコメントruleを参照しないが、文字列内コメント記号の解釈を左右する。
5. **過剰取得**: DEFAULT_MODEから全modeへの入口を単純に追うと、PHPではXML/HTML/Script/Style/HereDoc/Interpolationまで候補が広がる。これは必要性の証明ではなく、起点modeだけの追跡では切れないことの証拠である。
6. **閉包の最小性**: 今回は推移閉包の最小性を主張しない。rule-referenceについては「その辺を無視すれば本体構造を失う」という局所的根拠はあるが、mode/state/競合を含む全体の最小集合を証明していない。

## H. 自動化できた範囲と人手が必要な範囲

### 機械的に取得できる

- rule/fragment名、source span、mode所属。
- 同一文法内の識別子rule参照とその推移閉包。
- `pushMode`/`mode`/`popMode` 等の明示command。
- channel、skip、more、typeの構文上の記述。
- literal、action/predicateの存在、action内の識別子候補。
- Vocabulary/ATNを利用する既存実験では token type、symbolic/literal name、channel/mode名、ATN規模も取得できる。

### 設定または人手が必要

- どのruleをCOMMENT/STRING/RESERVEDの起点にするか。
- `HIDDEN`/`skip`/独自channelの意味。
- tokenを保持するのか除去するのか、文字列を1単位へまとめるのか。
- 文脈依存予約語、directive用token、埋込み言語の扱い。
- import/tokenVocab/delegate grammarの探索範囲。

### 取得できても意味解釈できない

- Java actionの任意コード、base class、外部helperの副作用。
- predicateの実行条件を文法テキストだけから完全に評価すること。
- Lexerの最長一致・規則優先順位をカテゴリ意味へ変換すること。
- channel名がコメントを表すという意味そのもの。

## I. 方法の成立条件・限界

この取得方法は、**同一Lexer grammarの静的構造を説明し、明示seedに必要なfragment/rule構成を集める**という条件では成立する。Luaの `COMMENT -> NESTED_STR` はその実例であり、参照閉包は直接参照で必要構造を取得した。

一方、**正しく利用するための十分なLexer入力モデルを得る方法**としては、次の条件が追加される。

- 起点カテゴリを人または検証済み設定が指定する。
- imported grammarとtokenVocabを解決する。
- modeの入口から出口までを別グラフとして追う。
- action/predicate、状態変数、base class/helperを静的または動的に調べる。
- 競合ruleを、少なくとも同一modeの先頭literal/文字集合/規則順序で評価する。
- 文字列の境界をコメント除去より先にLexer実行で得る。

この条件を満たすと、対象ruleだけでなくLexer全体に近い範囲が必要になることがある。PHPの11 mode候補への広がりは実測した解析結果、C#の状態依存は文法コード確認、actionの完全解釈不能は方法上の限界である。したがって「全Lexerが必須」と一律に断定するのではなく、**mode/state/競合がカテゴリの適用条件に関与する文法では、局所closureだけでは止められない**と結論する。

## J. 未実装と未検証

- `import`、`tokenVocab`、delegate grammarを複数ファイル間で解決する完全実装。
- ANTLRの公式ParseTree/ATNと本実験の軽量抽出結果の網羅的差分検証。
- action/predicateのJavaコンパイル単位・base class・外部helperの依存解決。
- modeを含む必要集合の最小性証明。
- `skip()`されたtokenを含めた共通token観測、CCFX prep変換、大規模性能評価。
- PHP heredoc、C#補間内コメント、全言語のimport grammar。
- 生成Lexer実行結果と本依存抽出器の自動突合せ。

既存の生成Lexer実行・metadata分類の実験は、前記の既存報告と `experiments/antlr-generated-lexer/` に保存されている。今回のJSON抽出結果は、生成Lexerの動作正しさではなく依存取得の証拠である。

## K. 再現コマンド・変更ファイル

リポジトリルートで次を実行する。

```sh
sh experiments/antlr-dependency-analysis/run.sh
```

今回の追加ファイル:

- `experiments/antlr-dependency-analysis/analyze.py`
- `experiments/antlr-dependency-analysis/run.sh`
- `experiments/antlr-dependency-analysis/README.md`
- `experiments/antlr-dependency-analysis/results/{lua,php,csharp}.json`（実行生成物）
- `docs/antlr-lexer-dependency-analysis-report.md`

今回、元の `CCFinderSW-1.0/grammarsv4`、`src/main/java`、ユーザー提供の読み取り専用C#生成Lexer snapshotは変更していない。既存の未コミット変更と `.gradle` の変更は上書き・削除していない。

使用条件は、リポジトリ内の元文法テキスト、Python 3、POSIX shellである。今回の依存抽出自体はANTLR runtimeを必要としない。生成Lexer/ATNを再確認する場合は、既存実験の `lib/antlr-4.7.1-complete.jar` とその `verify.sh` を使う。

## L. 次に検証するべき仮説

1. ANTLR toolが生成したATNから、各modeの入口・rule start・rule transition・token typeを抽出すれば、文法sourceのrule-reference不足をどこまで補えるか。
2. `LexerATNSimulator`の実行トレースを保存すれば、最長一致・rule順序・実際に通ったmodeをカテゴリ証拠として利用できるか。
3. `nextToken()`前後のtoken列を比較すれば、`skip`、`more`、`type`、actionによる差替えを共通形式で表現できるか。
4. seedの人手指定を維持したまま、mode入口・state・競合を「必要条件」と「過剰候補」に分離する停止規則を定義できるか。
5. Lua/PHP/C#の3例で得た依存分類を、import grammarと埋込み言語を持つHTML/JavaScript等へ適用したとき、個別特例なしに再現できるか。

## 最終結論

**`.g4`のrule名と本体参照を起点にfragment/lexer ruleの推移閉包を取得する方法は、明示された同一Lexer grammarの構造依存を得る条件では成立する。ただし、コメント・文字列・予約語を正しく利用するための十分な取得方法ではない。PHPのmode入口/出口、C#の補間状態、action/predicate、最長一致・規則順序・文字列とコメントの競合は参照追跡だけでは取得できず、modeグラフ、生成Lexer/ATN、Java処理の解析または実行、人手による意味設定が必要である。必要情報の範囲は文法ごとに異なり、状態依存・埋込み構文がある場合はLexer全体に近づくが、その結論は今回のmode抽出結果・文法コード確認・既存生成Lexer実験に基づくものであり、全Lexerについての最小性証明ではない。**
