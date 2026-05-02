"""PreToolUse: block PII / secret leaks in tracked markdown."""

import json
import re
import sys

if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

TARGET_PATH = re.compile(
    r"(^|/)(CHANGELOG|README)\.md$",
    re.IGNORECASE,
)

PATTERNS = (
    ("phone_thai", re.compile(r"\+66[\s\-]?\d{1,2}[\s\-]?\d{3,4}[\s\-]?\d{4}")),
    ("phone_ru", re.compile(r"\+7[\s\-]?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2}")),
    ("ssh_uri", re.compile(r"\b(?:root|ubuntu|admin)@[a-z0-9.\-]+\.[a-z]{2,}", re.IGNORECASE)),
    ("openai_key", re.compile(r"sk-[A-Za-z0-9_\-]{20,}")),
    ("aws_access", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("tg_bot_token", re.compile(r"\b\d{8,12}:[A-Za-z0-9_\-]{30,}\b")),
    ("ipv4_public", re.compile(
        r"\b(?!10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|127\.|0\.)"
        r"(?:25[0-5]|2[0-4]\d|[01]?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|[01]?\d?\d)){3}\b"
    )),
)


def _collect_new_text(tool_input):
    parts = []
    for key in ("content", "new_string", "newString", "text"):
        v = tool_input.get(key)
        if isinstance(v, str):
            parts.append(v)
    edits = tool_input.get("edits")
    if isinstance(edits, list):
        for edit in edits:
            if isinstance(edit, dict):
                v = edit.get("new_string") or edit.get("newString")
                if isinstance(v, str):
                    parts.append(v)
    return "\n".join(parts)


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0

    tool_input = payload.get("tool_input", {}) or {}
    path = tool_input.get("file_path") or tool_input.get("path") or ""
    if not path:
        return 0

    normalized = path.replace("\\", "/")
    if not TARGET_PATH.search(normalized):
        return 0

    new_text = _collect_new_text(tool_input)
    if not new_text:
        return 0

    for pattern_name, regex in PATTERNS:
        match = regex.search(new_text)
        if not match:
            continue
        snippet = match.group(0)
        sys.stderr.write(
            f"BLOCKED PII / secret leak in {path}\n"
            f"Pattern: {pattern_name}\n"
            f"Match:   {snippet}\n"
            f"Use anonymized refs or .env for secrets.\n"
        )
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
