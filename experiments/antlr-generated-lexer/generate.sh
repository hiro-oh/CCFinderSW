#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANTLR_JAR=${ANTLR_JAR:-"$ROOT/lib/antlr-4.7.1-complete.jar"}
OUT="$ROOT/experiments/antlr-generated-lexer/generated"
CLASSES="$ROOT/experiments/antlr-generated-lexer/classes"

rm -rf "$OUT" "$CLASSES"
mkdir -p "$OUT" "$CLASSES"

generate() {
    language=$1
    grammar=$2
    java -jar "$ANTLR_JAR" -o "$OUT/$language" "$ROOT/$grammar"
    find "$OUT/$language" -name '*.java' -print0 | xargs -0 javac -cp "$ANTLR_JAR" -d "$CLASSES/$language"
}

generate lua CCFinderSW-1.0/grammarsv4/lua/Lua.g4
generate php CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4
generate csharp CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4

find "$OUT" -type f -name '*.java' -print | sort > "$OUT/generated-files.txt"
find "$OUT" -type f -name '*.java' -exec sha256sum {} \; | sort > "$OUT/generated-sha256.txt"
{
    printf 'antlrJar=%s\n' "$ANTLR_JAR"
    printf 'antlrVersion=4.7.1\n'
    printf 'javaVersion='
    java -version 2>&1 | head -n 1
    printf 'grammarSha256\n'
    sha256sum "$ROOT/CCFinderSW-1.0/grammarsv4/lua/Lua.g4" "$ROOT/CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4" "$ROOT/CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4"
    printf 'generatedFiles\n'
    cat "$OUT/generated-files.txt"
} > "$OUT/build-manifest.txt"

printf 'Generated and compiled Lua, PHP, and C# lexers under %s\n' "$OUT"