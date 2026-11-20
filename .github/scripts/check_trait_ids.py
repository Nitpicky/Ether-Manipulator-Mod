#!/usr/bin/env python3
"""Fails the build when code checks a trait id that no player can pick.

Allowed ids: the choices in TraitChoices.java, the hidden aliases in Traits.java,
Module commons/badges, and KNOWN_LEFTOVER_IDS below. A KNOWN_LEFTOVER id is a
retired trait still referenced inside live code (marked // LEFTOVER at the use
site); remove it from the list once its last use is gone. A new unknown id is
almost always a typo or a retired trait creeping back in.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SRC = os.path.join(ROOT, "src", "main", "java")
MODULES = {"plasmic", "kinetic", "hydraulic", "pyrotechnic", "tectonic",
           "pneumatic", "botanic", "photonic", "gravitic"}

# Retired traits still referenced inside live formulas (delete after release).
KNOWN_LEFTOVER_IDS: set = set()


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def main():
    trait = os.path.join(SRC, "com", "ethermod", "trait")
    choices = set(re.findall(r'"([a-z_]+\.[a-z_]+)"', read(os.path.join(trait, "TraitChoices.java"))))
    aliases = set(re.findall(r'traitId\.equals\("([a-z_.]+)"\)', read(os.path.join(trait, "Traits.java"))))
    used = {}
    for d, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(d, f)
            for n, line in enumerate(read(p).splitlines(), 1):
                for m in re.finditer(r'"([a-z]+)\.([a-z_]+)"', line):
                    if m.group(1) in MODULES:
                        used.setdefault(m.group(0).strip('"'), []).append(f"{os.path.relpath(p, ROOT)}:{n}")
    bad, stale = [], []
    for tid, where in sorted(used.items()):
        if tid in choices or tid in aliases or ".common" in tid or tid.endswith(".badge"):
            continue
        if tid in KNOWN_LEFTOVER_IDS:
            continue
        bad.append(f"{tid}  ({', '.join(where[:3])})")
    for tid in sorted(KNOWN_LEFTOVER_IDS - set(used)):
        stale.append(tid)
    for s in stale:
        print(f"note: {s} is no longer used; remove it from KNOWN_LEFTOVER_IDS")
    if bad:
        print("Trait ids that no player can pick (typo or retired trait):")
        for b in bad:
            print("  " + b)
        return 1
    print(f"Trait ids OK ({len(used)} used, {len(KNOWN_LEFTOVER_IDS)} known leftovers)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
