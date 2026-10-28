# ANTLR rule and mode acquisition experiment

This experiment combines the existing source-oriented dependency analysis with
metadata from compiled Java lexers and a small runtime tracer. It does not
modify CCFinderSW production code or claim to parse all ANTLR syntax.

From the repository root:

```sh
sh experiments/antlr-rule-mode-acquisition/run.sh
```

The command regenerates and compiles the repository-local Lua, PHP, and C#
lexers with `lib/antlr-4.7.1-complete.jar`, creates combined acquisition JSON
under `results/`, and traces three PHP fixtures. The acquisition JSON records
source spans, references, fragments, commands, action identifiers, mode
incoming edges, structural mode reachability, rule order, and the
`Vocabulary`/ATN mapping. The TSV traces record the mode before and after each
`nextToken()` call.

The existing experiments remain the source of the lightweight grammar
extractor and generated-lexer metadata extractor. This directory only adds the
combination and runtime observation layer.
