#!/usr/bin/env python3
"""Syntax + lightweight structural checks for the mod's Java sources.

CI (with the real Minecraft/Fabric classpath) remains the type-check authority; this
catches parse errors and obviously stale references before burning a CI round-trip.
"""
import pathlib
import sys

import tree_sitter_java
from tree_sitter import Language, Parser

JAVA = Language(tree_sitter_java.language())
parser = Parser(JAVA)

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "src")
errors = 0
files = 0


def walk(node):
    yield node
    for child in node.children:
        yield from walk(child)


for path in sorted(root.rglob("*.java")):
    files += 1
    data = path.read_bytes()
    tree = parser.parse(data)
    problems = [n for n in walk(tree.root_node) if n.type == "ERROR" or n.is_missing]
    if problems:
        errors += 1
        print(f"\n{path}")
        for node in problems[:6]:
            line = node.start_point[0] + 1
            col = node.start_point[1] + 1
            snippet = data[node.start_byte:min(node.end_byte, node.start_byte + 90)].decode(
                "utf-8", "replace").replace("\n", "\\n")
            kind = "MISSING " + node.type if node.is_missing else "ERROR"
            print(f"  {line}:{col} {kind}: {snippet}")
        if len(problems) > 6:
            print(f"  ... {len(problems) - 6} more")

print(f"\nParsed {files} Java files; {errors} with syntax errors")
sys.exit(1 if errors else 0)
