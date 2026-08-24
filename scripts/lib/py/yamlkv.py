#!/usr/bin/env python3
"""Indentation-aware YAML key setter for the config files this repo patches.

Promoted from the 2-level `set_key` that lived inside init-paper.sh's
configure_sexidium_networked_backend_if_present heredoc, and generalized to N
levels because the network work needs both `network.role` (2 levels) and
`ui.resource-pack.url` (3 levels) -- the latter previously needed its own
bespoke 76-line patcher.

Deliberately NOT a YAML library:

  * These files are heavily commented, and every comment is load-bearing
    documentation (see config.yml). A round trip through PyYAML would delete
    every one of them and reorder the rest.
  * Paper and Sexidium both rewrite these files themselves; staying line-oriented
    means we only ever touch the lines we are asked to touch.

Handles the subset actually used: nested block mappings, `#` comments, blank
lines, and scalar values. It does NOT handle sequences, flow mappings or
multi-line scalars, and will refuse rather than guess.

Usage:
    yamlkv.py [--step N] <file> <dotted.key> <value> [<dotted.key> <value> ...]
    yamlkv.py [--step N] --get <file> <dotted.key>

--step is the indentation width of the file being edited, and it exists because not
every file this repo patches is one of ours. Sexidium's own configs indent with 2, which
is the default; SkinsRestorer's config.yml indents with 4, and editing it at the wrong
step is not a near miss -- `find_key` matches nothing, `set_key` INSERTS the key at its
own depth instead, and the result is a second, shallower run of keys glued onto the end
of the real block. YAML reads a shallower line as the end of one mapping and the start of
another, so the plugin dies in loadConfig() and registers no commands. That is not a
hypothetical: it took `/skin` down on a proxy and three backends at once.
"""
import re
import sys


def is_blank(line):
    return not line.strip() or line.lstrip().startswith("#")


def indent_of(line):
    return len(line) - len(line.lstrip())


def scalar(value):
    """Quote unless it is a bool/int/null, which Bukkit's getBoolean/getInt parse natively."""
    text = str(value)
    if text in ("true", "false", "null", "~") or re.fullmatch(r"-?\d+", text):
        return text
    if text == "":
        return "''"
    return "'" + text.replace("'", "''") + "'"


def find_key(lines, start, end, key, depth):
    """Index of `key:` at the given indent depth within [start, end), else None."""
    pattern = re.compile(r"^\s{%d}%s\s*:" % (depth, re.escape(key)))
    for i in range(start, end):
        if is_blank(lines[i]):
            continue
        if pattern.match(lines[i]):
            return i
    return None


def block_end(lines, header, depth):
    """End of the block owned by the header line: first later line indented <= depth."""
    for i in range(header + 1, len(lines)):
        if is_blank(lines[i]):
            continue
        if indent_of(lines[i]) <= depth:
            return i
    return len(lines)


def set_key(lines, dotted, value, step=2):
    parts = dotted.split(".")
    start, end, depth = 0, len(lines), 0

    for part in parts[:-1]:
        idx = find_key(lines, start, end, part, depth)
        if idx is None:
            # Create the missing parent section at the end of the current block.
            lines.insert(end, " " * depth + part + ":")
            idx, end = end, end + 1
        start, end = idx + 1, block_end(lines, idx, depth)
        depth += step

    leaf = parts[-1]
    idx = find_key(lines, start, end, leaf, depth)
    rendered = " " * depth + leaf + ": " + scalar(value)
    if idx is None:
        # Append inside the parent block, after its last non-blank line, so a
        # trailing comment block stays attached to what follows it.
        at = end
        while at > start and is_blank(lines[at - 1]):
            at -= 1
        lines.insert(at, rendered)
    else:
        lines[idx] = rendered
    return lines


def get_key(lines, dotted, step=2):
    parts = dotted.split(".")
    start, end, depth = 0, len(lines), 0
    for part in parts[:-1]:
        idx = find_key(lines, start, end, part, depth)
        if idx is None:
            return None
        start, end = idx + 1, block_end(lines, idx, depth)
        depth += step
    idx = find_key(lines, start, end, parts[-1], depth)
    if idx is None:
        return None
    return lines[idx].split(":", 1)[1].strip().strip("'\"")


def main(argv):
    step = 2
    if len(argv) >= 2 and argv[0] == "--step":
        try:
            step = int(argv[1])
        except ValueError:
            print("yamlkv.py: --step wants an integer", file=sys.stderr)
            return 2
        if step < 1:
            print("yamlkv.py: --step must be at least 1", file=sys.stderr)
            return 2
        argv = argv[2:]

    if len(argv) >= 3 and argv[0] == "--get":
        path = argv[1]
        with open(path, encoding="utf-8") as handle:
            lines = handle.read().splitlines()
        value = get_key(lines, argv[2], step)
        if value is None:
            return 1
        print(value)
        return 0

    if len(argv) < 3 or (len(argv) - 1) % 2 != 0:
        print(__doc__, file=sys.stderr)
        return 2

    path, pairs = argv[0], argv[1:]
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().splitlines()
    for i in range(0, len(pairs), 2):
        set_key(lines, pairs[i], pairs[i + 1], step)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
