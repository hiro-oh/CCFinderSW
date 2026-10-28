#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
BASE="$ROOT/experiments/antlr-generated-lexer"
ANTLR_JAR=${ANTLR_JAR:-"$ROOT/lib/antlr-4.7.1-complete.jar"}
GENERATED="$BASE/generated"
CLASSES="$BASE/classes"
METADATA="$BASE/metadata"
CLASSIFICATION="$BASE/classification"

sh "$BASE/generate.sh"
rm -rf "$METADATA" "$CLASSIFICATION"
mkdir -p "$METADATA" "$CLASSIFICATION" "$CLASSES/tools"
javac -cp "$ANTLR_JAR" -d "$CLASSES/tools" "$BASE/MetadataExtractor.java"

extract() {
    language=$1
    lexer=$2
    grammar=$3
    java -cp "$ANTLR_JAR:$CLASSES/$language:$CLASSES/tools" MetadataExtractor \
        "$language" "$lexer" "$grammar" "$METADATA/$language.json"
    python3 "$BASE/classify.py" "$METADATA/$language.json" "$BASE/ground-truth/$language.json" "$CLASSIFICATION/$language.json"
}

extract lua LuaLexer CCFinderSW-1.0/grammarsv4/lua/Lua.g4
extract php PhpLexer CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4
extract csharp CSharpLexer CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4

python3 - "$METADATA" "$CLASSIFICATION" <<'PY'
import json
import sys
from pathlib import Path

metadata_dir = Path(sys.argv[1])
classification_dir = Path(sys.argv[2])
for language in ("lua", "php", "csharp"):
    metadata = json.loads((metadata_dir / f"{language}.json").read_text())
    classification = json.loads((classification_dir / f"{language}.json").read_text())
    assert metadata["tokens"], language
    assert metadata["ruleNames"], language
    assert metadata["modeNames"], language
    assert metadata["channelNames"], language
    assert classification["items"], language
    assert set(classification["metrics"]) == {"COMMENT", "STRING", "RESERVED"}
print("metadata, classification, and metrics JSON validation: OK")
PY

printf 'Phase 1-4 verification succeeded.\n'
printf 'Generated Java: %s\n' "$GENERATED"
printf 'Metadata: %s\n' "$METADATA"
printf 'Classification: %s\n' "$CLASSIFICATION"