#!/usr/bin/env python3
"""Small, source-preserving ANTLR lexer dependency analysis experiment.

This is intentionally not a complete ANTLR parser.  It recognizes the
lexer-rule/mode structure needed to test the hypothesis that a seed rule's
reference closure is sufficient.  Unrecognized constructs are reported rather
than silently treated as regular expressions.
"""
import json
import re
import sys
from pathlib import Path


RULE = re.compile(r"^(?:(fragment)\s+)?([A-Z][A-Za-z0-9_]*)\s*:")
RULE_NAME_ONLY = re.compile(r"^\s*(?:(fragment)\s+)?([A-Z][A-Za-z0-9_]*)\s*$")
MODE = re.compile(r"^\s*mode\s+([A-Za-z_][A-Za-z0-9_]*)\s*;")
COMMANDS = re.compile(r"\b(pushMode|mode|channel|type|skip|more|popMode)\s*(?:\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\))?")
IDENT = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
LITERAL = re.compile(r"""'(?:\\.|[^'\\])*'|"(?:(?:\\.)|[^"\\])*" """, re.X)

KEYWORDS = {
    "true", "false", "if", "else", "for", "while", "return", "new", "int",
    "boolean", "String", "Token", "CommonToken", "super", "this", "null",
    "pushMode", "popMode", "mode", "channel", "type", "skip", "more",
}


def strip_line_comment(line):
    quote = None
    escaped = False
    for i, char in enumerate(line):
        if escaped:
            escaped = False
        elif char == "\\":
            escaped = True
        elif quote:
            if char == quote:
                quote = None
        elif char in "'\"":
            quote = char
        elif line[i:i + 2] == "//":
            return line[:i]
    return line


def parse(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    rules = []
    modes = ["DEFAULT_MODE"]
    current_mode = "DEFAULT_MODE"
    pending_fragment = False
    pending_rule_name = None
    current = None
    for number, raw in enumerate(lines, 1):
        mode_match = MODE.match(raw)
        if mode_match:
            if current:
                current["endLine"] = number - 1
                rules.append(current)
                current = None
            current_mode = mode_match.group(1)
            modes.append(current_mode)
            pending_fragment = False
            continue
        if raw.strip() == "fragment":
            pending_fragment = True
            continue
        if pending_rule_name and raw.strip().startswith(":"):
            if current:
                current["endLine"] = number - 1
                rules.append(current)
            current = {
                "name": pending_rule_name[1],
                "fragment": pending_rule_name[0],
                "mode": current_mode,
                "startLine": pending_rule_name[2],
                "text": raw[raw.index(":") + 1:],
            }
            pending_rule_name = None
            pending_fragment = False
            continue
        match = RULE.match(raw)
        if match:
            if current:
                current["endLine"] = number - 1
                rules.append(current)
            current = {
                "name": match.group(2),
                "fragment": bool(match.group(1) or pending_fragment),
                "mode": current_mode,
                "startLine": number,
                "text": raw[raw.index(":") + 1:],
            }
            pending_fragment = False
            pending_rule_name = None
            continue
        else:
            name_match = RULE_NAME_ONLY.match(raw)
            if name_match:
                pending_rule_name = (
                    bool(name_match.group(1) or pending_fragment),
                    name_match.group(2),
                    number,
                )
                pending_fragment = False
                continue
        if current:
            current["text"] += "\n" + raw
    if current:
        current["endLine"] = len(lines)
        rules.append(current)

    names = {rule["name"] for rule in rules}
    for rule in rules:
        source = rule["text"]
        semantic_source = "\n".join(strip_line_comment(line)
                                    for line in source.splitlines())
        rule["references"] = sorted({
            word for word in IDENT.findall(semantic_source)
            if word in names and word != rule["name"]
        })
        rule["commands"] = [
            {"name": name, "argument": argument}
            for name, argument in COMMANDS.findall(semantic_source)
        ]
        rule["literals"] = LITERAL.findall(semantic_source)
        action_text = "\n".join(
            line for line in semantic_source.splitlines()
            if "{" in line or "}" in line
        )
        rule["actionIdentifiers"] = sorted({
            word for word in IDENT.findall(action_text)
            if word not in names and word not in KEYWORDS
        })
        rule["uncertainty"] = []
        if "{" in source or "}" in source:
            rule["uncertainty"].append("action-or-predicate")
        if "EOF" in source:
            rule["uncertainty"].append("EOF")
    return {"path": str(path), "modes": modes, "rules": rules}


def classify(doc, requested):
    rules = doc["rules"]
    by_name = {rule["name"]: rule for rule in rules}
    reasons = {}
    seeds = []
    for rule in rules:
        text = rule["name"] + " " + "\n".join(
            strip_line_comment(line) for line in rule["text"].splitlines()
        )
        evidence = []
        if rule["name"] in requested:
            evidence.append("explicit human seed")
        if re.search(r"comment", text, re.I):
            evidence.append("rule name/body contains comment")
        if any(command["name"] == "channel" and command["argument"] in
               {"COMMENTS_CHANNEL", "PhpComments"} for command in rule["commands"]):
            evidence.append("comment-like channel command")
        if evidence:
            seeds.append(rule["name"])
            reasons[rule["name"]] = evidence

    direct = set()
    frontier = list(seeds)
    while frontier:
        name = frontier.pop()
        if name in direct or name not in by_name:
            continue
        direct.add(name)
        frontier.extend(by_name[name]["references"])

    seed_modes = {by_name[name]["mode"] for name in seeds}
    mode_edges = []
    for rule in rules:
        for command in rule["commands"]:
            if command["name"] == "pushMode" or command["name"] == "mode":
                mode_edges.append({
                    "fromRule": rule["name"], "fromMode": rule["mode"],
                    "toMode": command["argument"], "kind": command["name"],
                    "reason": "lexer command in rule body",
                })
            elif command["name"] == "popMode":
                mode_edges.append({
                    "fromRule": rule["name"], "fromMode": rule["mode"],
                    "toMode": "caller/previous mode", "kind": "popMode",
                    "reason": "lexer command in rule body",
                })
    related_modes = seed_modes | {
        edge["fromMode"] for edge in mode_edges
        if edge["toMode"] in seed_modes
    } | {
        edge["toMode"] for edge in mode_edges
        if edge["fromMode"] in seed_modes and edge["toMode"] in doc["modes"]
    }
    mode_rules = {
        rule["name"] for rule in rules if rule["mode"] in related_modes
    }
    state = sorted({
        item for name in direct for item in by_name[name]["actionIdentifiers"]
    })
    action_rules = {
        rule["name"] for rule in rules
        if rule["actionIdentifiers"] or rule["uncertainty"]
    }
    literals = sorted({
        literal for name in direct for literal in by_name[name]["literals"]
    })
    identifier_rules = [
        rule["name"] for rule in rules
        if rule["name"].upper() in {"IDENTIFIER", "NAME", "ID"}
    ]
    keyword_rules = [
        rule["name"] for rule in rules
        if rule["name"].upper() not in {"IDENTIFIER", "NAME", "ID"}
        and any(re.fullmatch(r"'[A-Za-z_][A-Za-z0-9_]*'", literal)
                for literal in rule["literals"])
    ]
    graph_edges = [
        {"from": rule["name"], "to": target, "kind": "rule-reference",
         "reason": "identifier in lexer rule body"}
        for rule in rules for target in rule["references"]
    ]
    return {
        "seedSelection": reasons,
        "seedRules": seeds,
        "directReferenceClosure": sorted(direct),
        "ruleReferenceEdges": graph_edges,
        "modeEdges": mode_edges,
        "seedModes": sorted(seed_modes),
        "relatedModesByModeEdges": sorted(related_modes),
        "rulesInRelatedModes": sorted(mode_rules),
        "actionOrPredicateRules": sorted(action_rules),
        "stateOrHelperIdentifiersReached": state,
        "literalEvidenceInClosure": literals,
        "identifierLikeRules": identifier_rules,
        "keywordLikeRules": keyword_rules,
        "cannotInferAutomatically": [
            "Whether a channel or skip command semantically means comment",
            "Whether a literal keyword is reserved in the language or contextual",
            "Java action/predicate semantics, helper methods, and base-class behavior",
            "Whether longest-match/rule-order competition changes the desired category",
            "A complete entry path for every mode without analyzing the whole lexer",
        ],
        "humanSuppliedSeeds": requested,
    }


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: analyze.py <grammar.g4> <output.json> [seed ...]")
    path, output, *requested = sys.argv[1:]
    doc = parse(Path(path))
    doc["analysis"] = classify(doc, requested)
    Path(output).write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")


if __name__ == "__main__":
    main()
