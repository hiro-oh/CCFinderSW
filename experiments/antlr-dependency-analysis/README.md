# ANTLR dependency-analysis experiment

This isolated experiment tests whether a comment/string/keyword seed can be
reduced to a sufficient lexer rule set by following rule references. It does
not modify CCFinderSW or regenerate the bundled sources.

`analyze.py` parses the source structure needed for the test and writes
machine-readable evidence:

- rule and fragment definitions with source spans;
- direct rule-reference edges and their transitive closure;
- mode transitions (`pushMode`, `mode`, `popMode`);
- action/predicate presence and identifiers reached from the closure;
- literal and identifier-like evidence for strings and reserved words;
- explicit uncertainty and human-supplied seeds.

It is deliberately conservative: it is not an ANTLR grammar compiler. Its
purpose is to expose where source inspection has to be supplemented by ATN
or runtime inspection, rather than to claim a minimal rule set.

Run from the repository root:

```sh
sh experiments/antlr-dependency-analysis/run.sh
```

Outputs are reproducible JSON under `results/` and are generated artifacts
excluded from the source change summary. The inputs are the repository-local
Lua, PHP, and C# lexer grammars. Seeds are passed explicitly because semantic
categories such as "comment" are not guaranteed by ANTLR names, channels, or
commands.
