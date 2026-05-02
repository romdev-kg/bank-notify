"""PreToolUse guard: block edits to secrets and sensitive runtime files."""

import json
import re
import sys

if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

SENSITIVE_REGEX = (
    re.compile("(^|/)\\.env(\\.[^/]+)?$"),
)


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
    for regex in SENSITIVE_REGEX:
        if regex.search(normalized):
            sys.stderr.write(
                f"BLOCKED edit to sensitive file: {path}\n"
                f"Reason: matches /{regex.pattern}/\n"
            )
            return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
