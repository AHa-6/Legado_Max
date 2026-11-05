#!/usr/bin/env python3
"""Audit Compose composable parameter order against UI-ARCHITECTURE §3.1.

§3.1 (强制): modifier 永远是第一个参数. This script reports every @Composable
function that takes a `modifier` parameter but does not list it first, plus
every bare `.sp` literal found in composable bodies (§15.4 强制).

Usage: python3 tools/check_modifier_order.py <file.kt> [file.kt ...]
"""
import re
import sys


def composable_functions(lines):
    """Yield (start_index, name, param_names) for each composable-looking fun."""
    i = 0
    while i < len(lines):
        # A composable signature may wrap; find `fun Name(` at any indent.
        m = re.match(r"^\s*(internal |private |public )?fun ([A-Z]\w*)\(", lines[i])
        if not m:
            i += 1
            continue
        name = m.group(2)
        # Collect signature lines until parens balance.
        depth = 0
        chunk = []
        j = i
        while j < len(lines):
            depth += lines[j].count("(") - lines[j].count(")")
            chunk.append(lines[j])
            if depth <= 0:
                break
            j += 1
        text = "\n".join(chunk)
        # Parameter names are the identifiers at the start of each param line.
        params = re.findall(r"^\s{4,}(\w+)\s*:", text, re.M)
        yield i, name, params
        i = j + 1


def main(paths):
    bad_order = []
    bad_sp = []
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().split("\n")
        for start, name, params in composable_functions(lines):
            if "modifier" in params and params[0] != "modifier":
                bad_order.append((path, start + 1, name, params))
        # Bare .sp literals outside comments inside function bodies.
        for idx, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("*") or stripped.startswith("//"):
                continue
            # lineHeight = 21.sp / fontSize = 14.sp — flag numeric .sp
            if re.search(r"\b\d+(\.\d+)?\.sp\b", line):
                bad_sp.append((path, idx, stripped))

    print("== §3.1 modifier-first violations ==")
    if not bad_order:
        print("none")
    for path, line, name, params in bad_order:
        print(f"{path.split('/')[-1]}:{line} {name} params={params}")

    print("\n== §15.4 bare .sp literals ==")
    if not bad_sp:
        print("none")
    for path, line, text in bad_sp:
        print(f"{path.split('/')[-1]}:{line} {text}")


if __name__ == "__main__":
    main(sys.argv[1:])