#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
BASE="$ROOT/experiments/antlr-dependency-analysis"
OUT="$BASE/results"
rm -rf "$OUT"
mkdir -p "$OUT"

python3 "$BASE/analyze.py" \
  "$ROOT/CCFinderSW-1.0/grammarsv4/lua/Lua.g4" "$OUT/lua.json" COMMENT LINE_COMMENT
python3 "$BASE/analyze.py" \
  "$ROOT/CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4" "$OUT/php.json" MultiLineComment SingleLineComment Comment
python3 "$BASE/analyze.py" \
  "$ROOT/CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4" "$OUT/csharp.json" \
  SINGLE_LINE_COMMENT DELIMITED_COMMENT SINGLE_LINE_DOC_COMMENT DELIMITED_DOC_COMMENT

python3 - "$OUT" <<'PY'
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
for language in ("lua", "php", "csharp"):
    doc = json.loads((out / f"{language}.json").read_text())
    assert doc["rules"], language
    assert doc["analysis"]["seedRules"], language
    assert doc["analysis"]["ruleReferenceEdges"], language
    print(language, "rules=", len(doc["rules"]),
          "seeds=", ",".join(doc["analysis"]["seedRules"]),
          "directClosure=", len(doc["analysis"]["directReferenceClosure"]),
          "modes=", ",".join(doc["analysis"]["relatedModesByModeEdges"]))
print("dependency-analysis verification: OK")
PY
