#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Contract checks for the Kotlin sources of GalaxianParts.

These sources are only type-checked inside a full ROM build, so a mistake that a
one-second check could catch costs a full rebuild to notice. Two of them have
already done so:

* A declaration named after a Kotlin scope function (`run`, `let`, `apply`,
  `also`, `with`) that takes a single lambda parameter shadows the stdlib one
  for every bare trailing-lambda call in the same body. `fun run(block: () ->
  Boolean)` inside TuningScreen made stdlib `run { ... }` resolve to that
  helper, so the five confirmation lambdas returned `Unit` where `Boolean` was
  expected.
* A Compose extension used without its import - `Column(Modifier.fillMaxSize()
  .padding(insets))` with no `androidx.compose.foundation.layout.padding` -
  fails as an unresolved reference at the build stage.

The comments, strings and delimiters are checked too: some of these files are
generated or rewritten by scripts, and an unbalanced file is corrupt rather than
merely wrong. A line that closes a block and then starts another statement
without a newline between them - `item { SpecGrid() }            item {` - is
rejected as well, since it parses only by accident and reads as a Composable
call in a non-Composable context.
"""
from __future__ import annotations

import pathlib
import re
import sys

SRC = pathlib.Path(__file__).resolve().parents[1] / "src"

# Names that must not be redeclared as a single-lambda function.
SCOPE_FUNCTIONS = ("run", "let", "apply", "also", "with")

# Words allowed to follow a closing brace on the same line: the branches of an
# escaped block. Anything else is a statement sharing a line with a block it does
# not belong to, which a script-generated rewrite produced once.
BLOCK_CONTINUATIONS = ("else", "catch", "finally", "while")

STATEMENT_AFTER_BLOCK = re.compile(r"\}[ \t]+(\w+)[ \t]*\{")

# Packages whose imported function names are Compose modifier extensions: a
# `Modifier.<name>(...)` chain link in a file without the import cannot compile.
# Only calls chained off `Modifier` are checked, so unrelated names such as
# `bytes.size()` are not mistaken for Compose extensions.
COMPOSE_PACKAGES = (
    "androidx.compose.foundation.layout.",
    "androidx.compose.foundation.",
    "androidx.compose.ui.draw.",
)

checks = 0


def require(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        print(f"FAIL: {message}")
        sys.exit(1)


def blank(match: re.Match[str]) -> str:
    """Replace a literal or comment with spaces, keeping its line count."""
    return re.sub(r"[^\n]", " ", match.group(0))


def without_literals(text: str) -> str:
    """The source with raw strings, strings, chars and comments blanked out."""
    text = re.sub(r'""".*?"""', blank, text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", blank, text, flags=re.S)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', blank, text)
    text = re.sub(r"'(?:\\.|[^'\\\n])*'", blank, text)
    return re.sub(r"//[^\n]*", blank, text)


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def balanced(text: str) -> str | None:
    """The first unbalanced delimiter in blanked source, if any."""
    pairs = {"(": ")", "[": "]", "{": "}"}
    closing = {value: key for key, value in pairs.items()}
    stack: list[str] = []
    for offset, char in enumerate(text):
        if char in pairs:
            stack.append(char)
        elif char in closing:
            if not stack or stack.pop() != closing[char]:
                return f"unbalanced '{char}' at line {line_of(text, offset)}"
    if stack:
        return f"unclosed '{stack[-1]}' at line {line_of(text, text.rfind(stack[-1]))}"
    return None


def parameters(text: str, open_paren: int) -> tuple[str, int]:
    """The parameter list starting at `open_paren` and its closing index."""
    depth = 0
    for index in range(open_paren, len(text)):
        if text[index] in "([{":
            depth += 1
        elif text[index] in ")]}":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : index], index
    return "", len(text)


def modifier_chains(text: str) -> list[tuple[str, int]]:
    """Every `.name(` link of every `Modifier...` chain in blanked source."""
    links: list[tuple[str, int]] = []
    for start in re.finditer(r"\bModifier\b", text):
        position = start.end()
        while True:
            link = re.match(r"\s*\.\s*(\w+)\s*\(", text[position:])
            if link is None:
                break
            links.append((link.group(1), position + link.start(1)))
            _, end = parameters(text, position + link.end() - 1)
            position = end + 1
    return links


def top_level_parameters(listing: str) -> list[str]:
    parts, depth, current = [], 0, ""
    for char in listing:
        if char in "([{<":
            depth += 1
        elif char in ")]}>":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    parts.append(current)
    return [part.strip() for part in parts if part.strip()]


files = sorted(SRC.rglob("*.kt"))
require(bool(files), "Kotlin sources are present")

sources = {path: without_literals(path.read_text(encoding="utf-8")) for path in files}

# Function names this tree imports from Compose as extension functions.
compose_extensions: set[str] = set()
for text in sources.values():
    for package in COMPOSE_PACKAGES:
        for name in re.findall(rf"^import {re.escape(package)}(\w+)$", text, re.M):
            if name[:1].islower():
                compose_extensions.add(name)

for path, text in sources.items():
    relative = path.relative_to(SRC)

    problem = balanced(text)
    require(problem is None, f"{relative}: {problem}")

    for match in STATEMENT_AFTER_BLOCK.finditer(text):
        word = match.group(1)
        require(word in BLOCK_CONTINUATIONS,
                f"{relative}:{line_of(text, match.start())}: '{word} {{' starts a "
                f"new statement on the same line as a closing brace; the block and "
                f"the statement that follows it need a line break between them")

    for match in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", text):
        name = match.group(1)
        if name not in SCOPE_FUNCTIONS:
            continue
        listing, _ = parameters(text, match.end() - 1)
        params = top_level_parameters(listing)
        shadowing = len(params) == 1 and "->" in params[0]
        require(not shadowing,
                f"{relative}:{line_of(text, match.start())}: fun {name}("
                f"{params[0] if params else ''}) shadows the stdlib {name}; "
                f"it would capture every `{name} {{ ... }}` in this body")

    imports = set(re.findall(r"^import (\S+)$", text, re.M))
    for name, offset in modifier_chains(text):
        if name not in compose_extensions:
            continue
        imported = any(entry.endswith(f".{name}") for entry in imports)
        require(imported,
                f"{relative}:{line_of(text, offset)}: Modifier.{name}(...) needs "
                f"an import of the Compose {name} extension")

print(f"PASS: {checks} Kotlin source checks")
