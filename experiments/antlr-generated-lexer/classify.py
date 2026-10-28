#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path


IDENTIFIER_NAMES = {"IDENTIFIER", "NAME", "ID"}
NUMBER_RE = re.compile(r"(NUMBER|INTEGER|FLOAT|DECIMAL|HEX|DIGIT|LITERAL)")
WORD_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def normalized(value):
    return (value or "").strip("'").strip('"')


def classify(token, channel_names):
    symbolic = token.get("symbolicName") or ""
    rule = token.get("ruleName") or ""
    literal = normalized(token.get("literalName"))
    channel = ""
    mode = token.get("mode")
    text = f"{symbolic} {rule}".upper()
    evidence = []

    if "COMMENT" in text:
        evidence.append("symbolic or rule name contains COMMENT")
        return "COMMENT", "HIGH", evidence
    if literal and WORD_RE.match(literal):
        evidence.append("fixed alphabetic literal")
        evidence.append("distinguished from punctuation and string rule tokens")
        return "RESERVED", "MEDIUM", evidence
    if any(word in text for word in ("STRING", "CHAR", "VERBATIUM", "INTERPOLATED")):
        evidence.append("symbolic or rule name identifies a string/character token")
        return "STRING", "HIGH", evidence
    if symbolic in IDENTIFIER_NAMES or rule in IDENTIFIER_NAMES:
        evidence.append("identifier token name")
        return "IDENTIFIER", "HIGH", evidence
    if NUMBER_RE.search(text):
        evidence.append("numeric token name")
        return "NUMBER", "MEDIUM", evidence
    if symbolic or literal:
        evidence.append("token metadata does not uniquely identify COMMENT, STRING, or RESERVED")
        return "OTHER", "LOW", evidence
    return "UNKNOWN", "LOW", ["no usable token name or literal"]


def truth_set(ground_truth, category):
    result = set()
    for item in ground_truth.get(category.lower(), []):
        result.add(item.get("symbolicName") or item.get("ruleName") or item.get("literal"))
    return {item for item in result if item}


def key(token):
    return token.get("symbolicName") or normalized(token.get("literalName")) or token.get("ruleName")


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: classify.py <metadata.json> <ground-truth.json> <output.json>")
    metadata = load(sys.argv[1])
    ground_truth = load(sys.argv[2])
    channel_names = metadata.get("channelNames", [])
    results = []
    predicted = {category: set() for category in ("COMMENT", "STRING", "RESERVED")}
    for token in metadata.get("tokens", []):
        category, confidence, evidence = classify(token, channel_names)
        item = {
            "tokenType": token.get("tokenType"),
            "symbolicName": token.get("symbolicName"),
            "ruleName": token.get("ruleName"),
            "literalName": token.get("literalName"),
            "displayName": token.get("displayName"),
            "channel": None,
            "mode": token.get("mode"),
            "category": category,
            "confidence": confidence,
            "evidence": evidence,
        }
        results.append(item)
        if category in predicted:
            predicted[category].add(key(token))

    metrics = {}
    errors = {}
    for category in ("COMMENT", "STRING", "RESERVED"):
        expected = truth_set(ground_truth, category)
        actual = predicted[category]
        tp = len(expected & actual)
        fp_items = sorted(actual - expected)
        fn_items = sorted(expected - actual)
        fp = len(fp_items)
        fn = len(fn_items)
        precision = tp / (tp + fp) if tp + fp else None
        recall = tp / (tp + fn) if tp + fn else None
        f1 = (2 * precision * recall / (precision + recall)) if precision is not None and recall is not None and precision + recall else None
        metrics[category] = {
            "TP": tp,
            "FP": fp,
            "FN": fn,
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "unknownCount": sum(item["category"] == "UNKNOWN" for item in results),
        }
        errors[category] = {"falsePositive": fp_items, "falseNegative": fn_items}

    output = {
        "language": metadata.get("language"),
        "lexerClass": metadata.get("lexerClass"),
        "source": str(Path(sys.argv[1]).resolve()),
        "classificationBasis": "static Vocabulary and ATN-derived metadata; no source execution",
        "items": results,
        "metrics": metrics,
        "errors": errors,
        "limitations": [
            "Channel is represented by channel names in metadata; token emission channel requires runtime execution",
            "Contextual keywords and action-dependent grouping are UNKNOWN or require language-specific rules",
            "Ground truth is manually authored from the grammar and is not inferred by this script",
        ],
    }
    Path(sys.argv[3]).write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()