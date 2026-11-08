#!/usr/bin/env python3
"""Cross-reference our own classes: catch calls to methods/fields that no longer exist.

This is not a type checker (CI is), but it reliably catches the mechanical-rename
fallout that has been the main source of breakage in this refactor.
"""
import pathlib
import re
import sys
from collections import defaultdict

import tree_sitter_java
from tree_sitter import Language, Parser

JAVA = Language(tree_sitter_java.language())
parser = Parser(JAVA)
SRC = pathlib.Path("src/main/java")


def walk(node):
    yield node
    for c in node.children:
        yield from walk(c)


def text(node, data):
    return data[node.start_byte:node.end_byte].decode("utf-8", "replace")


# Collect declared members per top-level type (including nested records/classes).
members = defaultdict(set)
types = {}
for path in sorted(SRC.rglob("*.java")):
    data = path.read_bytes()
    tree = parser.parse(data)
    for node in walk(tree.root_node):
        if node.type in ("class_declaration", "record_declaration", "interface_declaration",
                         "enum_declaration"):
            name_node = node.child_by_field_name("name")
            if name_node is None:
                continue
            name = text(name_node, data)
            types[name] = path
            body = node.child_by_field_name("body")
            if body is None:
                continue
            for m in walk(body):
                if m.type in ("method_declaration", "constructor_declaration"):
                    n = m.child_by_field_name("name")
                    if n is not None:
                        members[name].add(text(n, data))
                elif m.type in ("field_declaration", "constant_declaration"):
                    for d in m.children:
                        if d.type == "variable_declarator":
                            n = d.child_by_field_name("name")
                            if n is not None:
                                members[name].add(text(n, data))
                elif m.type == "record_declaration":
                    # record components are implicit accessors
                    params = m.child_by_field_name("parameters")
                    if params is not None:
                        for p in walk(params):
                            if p.type == "formal_parameter":
                                n = p.child_by_field_name("name")
                                if n is not None:
                                    members[name].add(text(n, data))
            if node.type == "record_declaration":
                params = node.child_by_field_name("parameters")
                if params is not None:
                    for p in walk(params):
                        if p.type == "formal_parameter":
                            n = p.child_by_field_name("name")
                            if n is not None:
                                members[name].add(text(n, data))
            if node.type == "enum_declaration":
                body2 = node.child_by_field_name("body")
                if body2 is not None:
                    for c in body2.children:
                        if c.type == "enum_constant":
                            n = c.child_by_field_name("name")
                            if n is not None:
                                members[name].add(text(n, data))

# Records inherit these; enums too.
for name in members:
    members[name] |= {"values", "valueOf", "name", "ordinal", "toString", "equals",
                      "hashCode", "getClass", "compareTo", "describeConstable", "builder"}

problems = []
for path in sorted(list(SRC.rglob("*.java")) +
                   list(pathlib.Path("src/test/java").rglob("*.java")) +
                   list(pathlib.Path("src/gametest/java").rglob("*.java"))):
    data = path.read_bytes()
    tree = parser.parse(data)
    for node in walk(tree.root_node):
        if node.type != "method_invocation":
            continue
        obj = node.child_by_field_name("object")
        nm = node.child_by_field_name("name")
        if obj is None or nm is None:
            continue
        obj_t = text(obj, data)
        method = text(nm, data)
        # Only check unambiguous `OurType.member(...)` static/qualified references.
        if obj_t in members and obj_t in types:
            if method not in members[obj_t]:
                problems.append((path, node.start_point[0] + 1, f"{obj_t}.{method}(...)"))

print(f"Indexed {len(types)} types")
if problems:
    print(f"\n{len(problems)} suspicious qualified calls:")
    for path, line, ref in problems:
        print(f"  {path}:{line}  {ref}")
else:
    print("No suspicious qualified calls to our own types")
sys.exit(1 if problems else 0)
