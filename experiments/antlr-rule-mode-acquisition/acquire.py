#!/usr/bin/env python3
"""Combine source-rule analysis with generated-lexer metadata.

This is deliberately a reporting/acquisition layer, not a replacement ANTLR
parser.  It makes the two different evidence sources explicit and computes
structural mode paths without claiming that every path is executable.
"""
import json
import sys
from collections import defaultdict, deque
from pathlib import Path


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def reachable(graph, start):
    seen = {start}
    queue = deque([start])
    while queue:
        node = queue.popleft()
        for target in graph.get(node, ()):
            if target not in seen:
                seen.add(target)
                queue.append(target)
    return sorted(seen)


def main():
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: acquire.py <dependency.json> <metadata.json> "
            "<target-mode> <output.json>"
        )
    dependency = load(sys.argv[1])
    metadata = load(sys.argv[2])
    target_mode = sys.argv[3]
    rules = dependency["rules"]
    by_name = {rule["name"]: rule for rule in rules}
    edges = dependency["analysis"]["modeEdges"]

    forward = defaultdict(set)
    reverse = defaultdict(set)
    for edge in edges:
        source = edge["fromMode"]
        target = edge["toMode"]
        if target in dependency["modes"]:
            forward[source].add(target)
            reverse[target].add(source)

    incoming = [
        {
            "rule": rule["name"],
            "mode": rule["mode"],
            "commands": rule["commands"],
            "sourceSpan": [rule["startLine"], rule["endLine"]],
        }
        for rule in rules
        if any(
            command["name"] in {"pushMode", "mode"}
            and command["argument"] == target_mode
            for command in rule["commands"]
        )
    ]
    target_rules = [
        {
            "name": rule["name"],
            "fragment": rule["fragment"],
            "order": index,
            "sourceSpan": [rule["startLine"], rule["endLine"]],
            "commands": rule["commands"],
        }
        for index, rule in enumerate(rules)
        if rule["mode"] == target_mode
    ]
    mode_entry_candidates = sorted(
        set(reverse.get(target_mode, ())) | {target_mode}
    )
    mode_path = {
        "targetMode": target_mode,
        "incomingRules": incoming,
        "transitionSources": sorted(reverse.get(target_mode, ())),
        "reverseModeClosure": reachable(reverse, target_mode),
        "forwardModeClosureFromDefault": reachable(forward, "DEFAULT_MODE"),
        "structuralPathExists": target_mode in reachable(forward, "DEFAULT_MODE"),
        "entryCandidates": mode_entry_candidates,
        "popModeSemantics": (
            "caller/previous stack mode; static graph cannot identify the "
            "concrete return mode"
        ),
    }

    closure = set(dependency["analysis"]["directReferenceClosure"])
    source_items = []
    for name in sorted(closure):
        rule = by_name[name]
        source_items.append(
            {
                "name": name,
                "mode": rule["mode"],
                "fragment": rule["fragment"],
                "sourceSpan": [rule["startLine"], rule["endLine"]],
                "references": rule["references"],
                "commands": rule["commands"],
                "literals": rule["literals"],
                "actionIdentifiers": rule["actionIdentifiers"],
                "uncertainty": rule["uncertainty"],
            }
        )

    metadata_by_rule = {
        item["ruleName"]: item
        for item in metadata["tokens"]
        if item.get("ruleName")
    }
    mapping = []
    for item in source_items:
        token = metadata_by_rule.get(item["name"])
        mapping.append(
            {
                "rule": item["name"],
                "sourceMode": item["mode"],
                "sourceSpan": item["sourceSpan"],
                "generatedTokenType": token["tokenType"] if token else None,
                "generatedMode": token["mode"] if token else None,
                "mapping": "ATN.ruleToTokenType/Vocabulary"
                if token
                else "no token mapping (fragment or non-token rule)",
            }
        )

    same_mode = defaultdict(list)
    for index, rule in enumerate(rules):
        same_mode[rule["mode"]].append((index, rule["name"]))
    competition = {
        mode: [
            {"order": index, "rule": name}
            for index, name in ordered
        ]
        for mode, ordered in same_mode.items()
        if mode in mode_entry_candidates
    }
    document = {
        "grammar": dependency["path"],
        "targetMode": target_mode,
        "seedRules": dependency["analysis"]["seedRules"],
        "sourceAcquisition": {
            "rules": source_items,
            "modePath": mode_path,
            "ruleOrderByRelevantMode": competition,
            "stateAndHelperCandidates": dependency["analysis"][
                "stateOrHelperIdentifiersReached"
            ],
        },
        "generatedAcquisition": {
            "lexerClass": metadata["lexerClass"],
            "ruleNames": metadata["ruleNames"],
            "modeNames": metadata["modeNames"],
            "channelNames": metadata["channelNames"],
            "atn": metadata["atn"],
            "tokenMappings": mapping,
            "limitations": metadata["limitations"],
        },
        "interpretation": {
            "staticModePathsAreCandidates": True,
            "runtimeModeStackAndActionsObservedElsewhere": True,
            "ruleOrderIsRecordedButLongestMatchStillRequiresLexerSemantics": True,
        },
    }
    Path(sys.argv[4]).write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
