#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANTLR_JAR=${ANTLR_JAR:-"$ROOT/lib/antlr-4.7.1-complete.jar"}
GRAMMAR=$1
LEXER_CLASS=$2
SOURCE=$3
WORK=${TMPDIR:-/tmp}/ccfsw-antlr-java-lexer
GEN="$WORK/generated"
CLASSES="$WORK/classes"
rm -rf "$WORK"
mkdir -p "$GEN" "$CLASSES"

java -jar "$ANTLR_JAR" -o "$GEN" "$GRAMMAR"
find "$GEN" -name '*.java' -print0 | xargs -0 javac -cp "$ANTLR_JAR" -d "$CLASSES"
javac -cp "$ANTLR_JAR:$CLASSES" -d "$CLASSES" "$ROOT/experiments/antlr-java-lexer/TokenDump.java"
java -cp "$ANTLR_JAR:$CLASSES" TokenDump "$LEXER_CLASS" "$SOURCE"