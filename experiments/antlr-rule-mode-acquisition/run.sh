#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
BASE="$ROOT/experiments/antlr-rule-mode-acquisition"
ANTLR_JAR=${ANTLR_JAR:-"$ROOT/lib/antlr-4.7.1-complete.jar"}
GEN="$ROOT/experiments/antlr-generated-lexer"
OUT="$BASE/results"

sh "$GEN/verify.sh" >/dev/null
rm -rf "$OUT"
mkdir -p "$OUT"
python3 "$ROOT/experiments/antlr-dependency-analysis/analyze.py" \
  "$ROOT/CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4" "$OUT/php-dependency.json" \
  MultiLineComment SingleLineComment ShellStyleComment Comment \
  PHPEndSingleLineComment CommentQuestionMark CommentEnd
python3 "$ROOT/experiments/antlr-dependency-analysis/analyze.py" \
  "$ROOT/CCFinderSW-1.0/grammarsv4/lua/Lua.g4" "$OUT/lua-dependency.json" \
  COMMENT LINE_COMMENT NESTED_STR
python3 "$ROOT/experiments/antlr-dependency-analysis/analyze.py" \
  "$ROOT/CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4" "$OUT/csharp-dependency.json" \
  SINGLE_LINE_COMMENT DELIMITED_COMMENT SINGLE_LINE_DOC_COMMENT DELIMITED_DOC_COMMENT

python3 "$BASE/acquire.py" "$OUT/php-dependency.json" \
  "$GEN/metadata/php.json" SingleLineCommentMode "$OUT/php-acquisition.json"
python3 "$BASE/acquire.py" "$OUT/lua-dependency.json" \
  "$GEN/metadata/lua.json" DEFAULT_MODE "$OUT/lua-acquisition.json"
python3 "$BASE/acquire.py" "$OUT/csharp-dependency.json" \
  "$GEN/metadata/csharp.json" INTERPOLATION_STRING "$OUT/csharp-acquisition.json"

mkdir -p "$GEN/classes/tools"
javac -cp "$ANTLR_JAR" -d "$GEN/classes/tools" "$BASE/TraceLexer.java"
for fixture in "$BASE"/fixtures/*.php; do
    name=$(basename "$fixture" .php)
    java -cp "$ANTLR_JAR:$GEN/classes/php:$GEN/classes/tools" TraceLexer PhpLexer "$fixture" \
        > "$OUT/$name.trace.tsv"
done

python3 - "$OUT" <<'PY'
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
php = json.loads((out / "php-acquisition.json").read_text())
assert php["sourceAcquisition"]["modePath"]["structuralPathExists"]
assert any(item["rule"] == "SingleLineComment" for item in php["sourceAcquisition"]["modePath"]["incomingRules"])
assert "SingleLineCommentMode" in php["generatedAcquisition"]["modeNames"]
for trace in out.glob("*.trace.tsv"):
    text = trace.read_text()
    assert "before=" in text and "after=" in text and "type=" in text
print("combined acquisition and runtime trace validation: OK")
PY
printf 'Acquisition results: %s\n' "$OUT"
