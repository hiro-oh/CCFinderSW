#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUN="$ROOT/experiments/antlr-java-lexer/run.sh"
GRAMMARS="$ROOT/CCFinderSW-1.0/grammarsv4"
OUT=${TMPDIR:-/tmp}/ccfsw-antlr-java-lexer-verify
rm -rf "$OUT"
mkdir -p "$OUT"

sh "$RUN" "$GRAMMARS/lua/Lua.g4" LuaLexer "$ROOT/experiments/antlr-java-lexer/inputs/long-brackets.lua" > "$OUT/lua.txt"
sh "$RUN" "$GRAMMARS/php/PhpLexer.g4" PhpLexer "$ROOT/experiments/antlr-java-lexer/inputs/mixed.php" > "$OUT/php.txt"
sh "$RUN" "$GRAMMARS/csharp/CSharpLexer.g4" CSharpLexer "$ROOT/experiments/antlr-java-lexer/inputs/strings.cs" > "$OUT/csharp.txt"

grep -q 'COMMENT' "$OUT/lua.txt"
grep -q 'INTERPOLATED_VERBATIUM_STRING_START' "$OUT/csharp.txt"
grep -q 'HtmlStyleOpen' "$OUT/php.txt"
grep -q 'Comment' "$OUT/php.txt"
cat "$OUT/lua.txt" "$OUT/php.txt" "$OUT/csharp.txt"