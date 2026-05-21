# tree-sitter モード使用ガイド

## 概要

CCFinderSW に tree-sitter ベースの解析モード（**tree-sitter モード**）が追加されました．通常モード・ANTLR モードと並列に使用できます．

## 前提条件

### 1. tree-sitter CLI のインストール

tree-sitter コマンドラインツールがインストールされていることを確認してください．

```bash
tree-sitter --version
```

インストール方法（例：Ubuntu/Debian）:
```bash
npm install -g tree-sitter-cli
```

> **注意**: バージョン固定を推奨（例: `npm install -g tree-sitter-cli@0.20.8`）

### 2. サブモジュールの初期化

リポジトリをクローン後，tree-sitter grammar のサブモジュールを初期化：

```bash
cd CCFinderSW
git submodule update --init --recursive
```

## 起動方法

### 通常モード（既存）

```bash
java -jar CCFinderSW-1.0.jar -d src -l java -o output
```

### ANTLR モード（既存）

```bash
java -jar CCFinderSW-1.0.jar -d src -l java -antlr java -o output
```

### tree-sitter モード（新規）

#### 1. デフォルト grammar + 既定の highlights.scm を使う

```bash
java -jar CCFinderSW-1.0.jar -d src -l java -ts java -o output
```

- `-ts java`: 拡張子マッチの正規表現（Java の場合は単純に `java`）
- `-l java`: `treesitter-config.json` に登録された言語キー

#### 2. デフォルト grammar + カスタム ccfsw.scm を使う

```bash
java -jar CCFinderSW-1.0.jar -d src -l java-custom -ts java -o output
```

`treesitter-queries/java-custom/ccfsw.scm` を使用します．

#### 3. 複数言語

```bash
java -jar CCFinderSW-1.0.jar -d src -l python -ts py -o output
java -jar CCFinderSW-1.0.jar -d src -l cpp -ts "h|hh|hpp|c|cc|cpp" -o output
```

## 新言語追加手順

### 例: Go 言語を追加する

#### Step 1: grammar サブモジュールを追加

```bash
git submodule add https://github.com/tree-sitter/tree-sitter-go \
  treesitter-grammars/tree-sitter-go
cd treesitter-grammars/tree-sitter-go && git checkout <コミットハッシュ>
cd - && git add . && git commit -m "Add tree-sitter-go"
```

#### Step 2: treesitter-config.json にエントリを追加

```json
{
  "go": {
    "grammar": "treesitter-grammars/tree-sitter-go",
    "query":   "treesitter-grammars/tree-sitter-go/queries/highlights.scm",
    "tsLanguageName": "go"
  }
}
```

#### Step 3: 起動

```bash
java -jar CCFinderSW-1.0.jar -d src -l go -ts go -o output
```

**完全な手作業準備は不要**です！grammar の highlights.scm が自動的に使われます．

### カスタム query を使う場合

highlights.scm の capture 分類が不十分な場合，自作 query を置くことができます：

#### Step 1: カスタム query ファイルを作成

```bash
mkdir -p treesitter-queries/go-custom
touch treesitter-queries/go-custom/ccfsw.scm
```

#### Step 2: ccfsw.scm にクエリを記述

```scheme
(line_comment)  @comment
(block_comment) @comment
(string_literal) (raw_string_literal) (interpreted_string_literal) @string
["if" "else" "for" "switch" "case" ...] @keyword
```

#### Step 3: treesitter-config.json を更新

```json
{
  "go-custom": {
    "grammar": "treesitter-grammars/tree-sitter-go",
    "query":   "treesitter-queries/go-custom/ccfsw.scm",
    "tsLanguageName": "go"
  }
}
```

#### Step 4: 起動

```bash
java -jar CCFinderSW-1.0.jar -d src -l go-custom -ts go -o output
```

## capture 名の分類をカスタマイズする

`treesitter-queries/capture-categories.json` で capture 名の分類ルールを定義します．

### 既定の分類

```json
{
  "comment": {
    "prefix": ["comment"]
  },
  "string": {
    "prefix": ["string", "character"]
  },
  "reserved": {
    "prefix": ["keyword", "reserved", "reserve"],
    "exact": []
  }
}
```

### カスタマイズ例：`@type.builtin` も予約語として扱う

```json
{
  "reserved": {
    "prefix": ["keyword", "reserved", "reserve", "type.builtin"],
    "exact": []
  }
}
```

変更後はビルド不要．すぐに反映されます．

### 言語別オーバーライド（将来拡張）

個別の言語ごとに分類ルールを上書きしたい場合：

```bash
mkdir -p treesitter-queries/go-custom
touch treesitter-queries/go-custom/capture-categories.json
```

内容は `treesitter-queries/capture-categories.json` と同じスキーマで記述可能（実装予定）．

## 各モードの比較

| 項目 | 通常モード | ANTLR モード | tree-sitter モード |
| --- | --- | --- | --- |
| **セットアップ** | `comment/*.txt`, `reserved/*.txt` | `.g4` グラマーファイル | `treesitter-config.json` のみ |
| **新言語追加コスト** | 高（テキストファイル 2 つ手作成） | 中（`.g4` を探すか作成） | 低（registry に 1 行追記） |
| **予約語/コメント判定** | `Set<String>` + 正規表現 | 正規表現 | tree-sitter query + 範囲ベース |
| **zeroTokenCheck** | あり（Java/C/C++） | なし | なし |
| **処理速度** | 速 | 中～速 | 中（CLI 呼び出しコスト） |

## トラブルシューティング

### `tree-sitter query: command not found`

tree-sitter CLI がインストールされていません．上記の「tree-sitter CLI のインストール」を参照してください．

### `Unknown tree-sitter language key: xxx`

`treesitter-config.json` に `-l` で指定した language key が定義されていません．

**確認方法**:
```bash
grep -n '"xxx"' treesitter-config.json
```

### `No such file or directory: treesitter-config.json`

`treesitter-config.json` がプロジェクトルートにありません．ビルドディレクトリではなく，CCFinderSW のルートに配置してください．

```bash
pwd  # CCFinderSW のルート確認
ls -la treesitter-config.json
```

### capture-categories.json が見つからない

```bash
ls -la treesitter-queries/capture-categories.json
```

初回実行時には自動作成されません．リポジトリに同梱されている必要があります．


