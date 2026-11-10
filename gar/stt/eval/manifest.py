"""GAR speech eval: reads clips.yml (the small YAML subset it is written in, stdlib only) and holds the
paths the eval scripts share."""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MANIFEST = os.path.join(HERE, "clips.yml")
CACHE = os.path.expanduser("~/.cache/gar")
EVAL_DIR = os.path.join(CACHE, "stt-eval")
CLIPS_DIR = os.path.join(EVAL_DIR, "clips")
RESULTS_DIR = os.path.join(EVAL_DIR, "results")
MODELS_DIR = os.path.join(CACHE, "stt")
BUILD_DIR = os.path.join(CACHE, "whisper-mac")

_KEY = re.compile(r"^([a-z_]+):\s*(.*)$")


def _unquote(text):
    if text.startswith('"') and text.endswith('"') and len(text) >= 2:
        return text[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    return text


def _flow_items(inner):
    """Splits a flow list's inside on the commas outside double quotes."""
    items, current, quoted, escaped = [], "", False, False
    for ch in inner:
        if escaped:
            escaped = False
        elif quoted and ch == "\\":
            escaped = True
        elif ch == '"':
            quoted = not quoted
        elif ch == "," and not quoted:
            items.append(current.strip())
            current = ""
            continue
        current += ch
    items.append(current.strip())
    return [_unquote(item) for item in items]


def _scalar(text):
    text = text.strip()
    if text.startswith("[") and text.endswith("]"):
        inner = text[1:-1].strip()
        return _flow_items(inner) if inner else []
    if text.startswith('"') and text.endswith('"') and len(text) >= 2:
        return _unquote(text)
    if text in ("true", "false"):
        return text == "true"
    return text


def load(path=MANIFEST):
    """Returns (top-level keys, clips): `key: value` lines, then `clips:` with one `- key: value`
    block per clip. Values are plain or double-quoted strings, true/false, or flow lists of plain
    strings without commas or double-quoted ones."""
    top, clips = {}, []
    in_clips = False
    with open(path, encoding="utf-8") as f:
        for number, raw in enumerate(f, 1):
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if not line.startswith(" "):
                m = _KEY.match(line)
                if not m:
                    raise ValueError(f"{path}:{number}: can't read {line!r}")
                in_clips = m.group(1) == "clips"
                if not in_clips:
                    top[m.group(1)] = _scalar(m.group(2))
                continue
            if not in_clips:
                raise ValueError(f"{path}:{number}: indented line outside clips")
            body = line.strip()
            if body.startswith("- "):
                clips.append({})
                body = body[2:].strip()
            m = _KEY.match(body)
            if not m or not clips:
                raise ValueError(f"{path}:{number}: can't read {line!r}")
            clips[-1][m.group(1)] = _scalar(m.group(2))
    ids = [c.get("id") for c in clips]
    for clip in clips:
        for key in ("id", "text", "type"):
            if key not in clip:
                raise ValueError(f"{path}: clip {clip.get('id')} has no {key}")
        clip.setdefault("names", [])
        clip.setdefault("prompt", [])
        clip.setdefault("noise", False)
    if len(set(ids)) != len(ids):
        raise ValueError(f"{path}: duplicate clip ids")
    return top, clips


def clip_wav(clip):
    """Where a clip's audio is: its `wav`, or <id>.wav made by make_clips.py."""
    return os.path.join(CLIPS_DIR, clip.get("wav") or clip["id"] + ".wav")
