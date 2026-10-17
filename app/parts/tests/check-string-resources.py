#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Contract checks for GalaxianParts string resources.

Runs on the host with no AOSP tree: every locale file must be well-formed XML and
every string value must use Android's escaping rules, so aapt2 cannot fail the
build on a resource we could have checked here. The apostrophe rule is the one
that reaches us most often: inside a <string> a bare ' ends the value early, so it
has to be written as \\' (or the whole value wrapped in double quotes).
"""
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

RES = pathlib.Path(__file__).resolve().parents[1] / "res"
checks = 0


def require(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        print(f"FAIL: {message}")
        sys.exit(1)


def value_of(element: ET.Element) -> str:
    """The text aapt2 sees, without markup placeholders or comments."""
    return "".join(element.itertext())


def unescaped_apostrophe(value: str) -> bool:
    stripped = value.strip()
    if len(stripped) > 1 and stripped.startswith('"') and stripped.endswith('"'):
        return False  # Quoted values do not need escaping.
    for index, char in enumerate(value):
        if char == "'" and (index == 0 or value[index - 1] != "\\"):
            return True
    return False


files = sorted(RES.rglob("*.xml"))
require(bool(files), "resource XML files are present")

for path in files:
    relative = path.relative_to(RES)
    try:
        tree = ET.parse(path)
    except ET.ParseError as error:
        print(f"FAIL: {relative}: not well-formed XML: {error}")
        sys.exit(1)
    checks += 1
    for element in tree.getroot():
        if element.tag not in ("string", "item"):
            continue
        value = value_of(element)
        name = element.get("name") or element.get("quantity") or element.tag
        require(not unescaped_apostrophe(value),
                f"{relative}: <{element.tag} {name}> has an "
                f"unescaped apostrophe: {value.strip()[:80]}")

print(f"PASS: {checks} string resource checks")
