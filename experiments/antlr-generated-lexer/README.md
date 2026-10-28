# Generated ANTLR Java Lexer experiment

This experiment generates Java lexers from the repository-local Lua, PHP, and C# grammars, preserves the generated Java source in `generated/`, extracts static metadata through the compiled lexer and ANTLR runtime API, and classifies token metadata without reconstructing regular expressions.

## Run

From the repository root:

```sh
sh experiments/antlr-generated-lexer/verify.sh
```

`generate.sh` uses `lib/antlr-4.7.1-complete.jar`, writes generated Java to `generated/{lua,php,csharp}`, compiles classes into `classes/`, and records `generated-files.txt`, `generated-sha256.txt`, and `build-manifest.txt`. The generated Java is intentionally kept in the repository workspace so it can be inspected. It is not treated as a stable source artifact because it is reproducible from the grammar, tool version, and command.

`MetadataExtractor.java` uses `Vocabulary`, `getRuleNames()`, `getModeNames()`, `getChannelNames()`, and `getATN()` from compiled lexer classes. `ATN.ruleToTokenType` is used where available to associate token types with lexer rules. Implicit literal token types are retained with a documented missing rule mapping.

`classify.py` reads the generated metadata and manually authored ground truth under `ground-truth/`. It writes static classification results and category metrics under `classification/`. The metrics are about metadata classification, not runtime token recognition. `N/A` is represented as JSON `null` when a denominator is zero.

## Inputs and ground truth

The source grammars are:

- `CCFinderSW-1.0/grammarsv4/lua/Lua.g4`
- `CCFinderSW-1.0/grammarsv4/php/PhpLexer.g4`
- `CCFinderSW-1.0/grammarsv4/csharp/CSharpLexer.g4`

Ground truth was written from those grammar files, with source line evidence. It is deliberately separate from the classifier.

## Limitations

The static extractor cannot prove runtime channel changes, `skip()` behavior, semantic predicates, action effects, or contextual keyword meaning. Those require source inspection or lexer execution. This experiment does not modify CCFinderSW production tokenization or generate CCFX prep.