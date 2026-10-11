#!/usr/bin/env python3
"""严格读取 OEM LayoutData.lua 字面量，生成独立指标 bplist；绝不执行 Lua。"""
import argparse
import math
import pathlib
import plistlib
import re

MAX_SOURCE = 4 * 1024 * 1024
MAX_GLYPHS = 4096
MAX_FONTS = 256


class LiteralParser:
    # 仅接受 return、表、字符串、有限十进制数字、布尔值；不接受表达式或函数。
    token = re.compile(r'\s+|--[^\n]*|(?P<number>-?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)|(?P<id>[A-Za-z_][A-Za-z_0-9]*)|(?P<punct>[{}\[\]=,;])')

    def __init__(self, source):
        if len(source.encode("utf-8")) > MAX_SOURCE:
            raise ValueError("LayoutData exceeds size limit")
        self.source, self.pos, self.count = source, 0, 0
        self.current = self.next_token()

    def next_token(self):
        s = self.source
        while self.pos < len(s):
            if s[self.pos] in "\"'":
                quote = s[self.pos]
                self.pos += 1
                chars = []
                while self.pos < len(s):
                    ch = s[self.pos]
                    self.pos += 1
                    if ch == quote:
                        return "string", "".join(chars)
                    if ch in "\r\n":
                        raise ValueError("unescaped newline in string")
                    if ch == "\\":
                        if self.pos >= len(s):
                            raise ValueError("unfinished string escape")
                        ch = s[self.pos]
                        self.pos += 1
                        escapes = {"a": "\a", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t", "v": "\v", "\\": "\\", '"': '"', "'": "'"}
                        if ch in escapes:
                            ch = escapes[ch]
                        elif ch.isascii() and ch.isdigit():
                            digits = ch
                            while len(digits) < 3 and self.pos < len(s) and s[self.pos] in "0123456789":
                                digits += s[self.pos]
                                self.pos += 1
                            if int(digits) > 255:
                                raise ValueError("invalid decimal escape")
                            ch = chr(int(digits))
                        elif ch == "x":
                            digits = s[self.pos:self.pos + 2]
                            if not re.fullmatch(r"[0-9a-fA-F]{2}", digits):
                                raise ValueError("invalid hex escape")
                            self.pos += 2
                            ch = chr(int(digits, 16))
                        else:
                            raise ValueError("unsupported string escape")
                    chars.append(ch)
                raise ValueError("unterminated string")
            match = self.token.match(s, self.pos)
            if not match:
                raise ValueError(f"non-literal Lua at offset {self.pos}")
            self.pos = match.end()
            if match.lastgroup:
                return match.lastgroup, match.group()
        return "eof", ""

    def take(self, kind, value=None):
        if self.current[0] != kind or (value is not None and self.current[1] != value):
            raise ValueError(f"expected {kind} {value}, got {self.current}")
        result = self.current[1]
        self.current = self.next_token()
        return result

    def value(self, depth=0):
        self.count += 1
        if depth > 16 or self.count > 300000:
            raise ValueError("literal complexity limit")
        kind, value = self.current
        if kind == "string":
            return self.take("string")
        if kind == "number":
            number = float(self.take("number"))
            if not math.isfinite(number):
                raise ValueError("non-finite number")
            return number
        if kind == "id" and value in ("true", "false"):
            self.take("id")
            return value == "true"
        self.take("punct", "{")
        fields, array = {}, []
        while self.current != ("punct", "}"):
            kind, value = self.current
            if kind == "punct" and value == "[":
                self.take("punct", "[")
                key = self.take("string")
                self.take("punct", "]")
                self.take("punct", "=")
                item = self.value(depth + 1)
                if key in fields:
                    raise ValueError("duplicate table key")
                fields[key] = item
            elif kind == "id" and value not in ("true", "false"):
                key = self.take("id")
                self.take("punct", "=")
                if key in fields:
                    raise ValueError("duplicate table key")
                fields[key] = self.value(depth + 1)
            else:
                array.append(self.value(depth + 1))
            if self.current[0] == "punct" and self.current[1] in (",", ";"):
                self.take("punct")
            elif self.current != ("punct", "}"):
                raise ValueError("expected table separator")
        self.take("punct", "}")
        if fields and array:
            raise ValueError("mixed table not supported")
        return array if array else fields

    def parse(self):
        self.take("id", "return")
        result = self.value()
        self.take("eof")
        return result


def number(value, low=-8192, high=8192):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError("invalid metric number")
    return value


def pair(value):
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("expected metric pair")
    return [number(v) for v in value]


def rectangle(value, texture=False):
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("expected metric rectangle")
    origin, size = pair(value[0]), pair(value[1])
    if min(size) < 0 or (texture and (min(origin) < 0 or any(v != int(v) for v in origin + size))):
        raise ValueError("invalid metric rectangle")
    return [origin, size]


def validate(data):
    chars = data["characters"]
    if not isinstance(chars, dict) or not 0 < len(chars) <= MAX_GLYPHS:
        raise ValueError("invalid character table")
    for char, glyph in chars.items():
        if len(char) != 1 or 0xD800 <= ord(char) <= 0xDFFF:
            raise ValueError("character must be one Unicode scalar")
        pair(glyph["advance"])
        rectangle(glyph["boundingBox"])
        rectangle(glyph["textureBoundingBox"], texture=True)
        if type(glyph["isWhitespace"]) is not bool:
            raise ValueError("invalid whitespace flag")
    number(data["height"], 1, 4096)
    number(data["lineOriginY"], 0, data["height"])
    for field in ("stable", "hasMonoSpaceFlag", "hasMonoSpacedDigits"):
        if type(data[field]) is not bool:
            raise ValueError(f"invalid {field}")
    kerning = data.get("kerningTable", {})
    if not isinstance(kerning, dict) or len(kerning) > MAX_GLYPHS:
        raise ValueError("invalid kerning table")
    total = 0
    for left, row in kerning.items():
        if left not in chars or not isinstance(row, dict):
            raise ValueError("invalid kerning row")
        total += len(row)
        if total > 100000:
            raise ValueError("kerning limit")
        for right, value in row.items():
            if right not in chars:
                raise ValueError("unknown kerning character")
            number(value)
    return data


def extract(root, manifest):
    resources = plistlib.loads(manifest.read_bytes())["resourcePaths"]
    names = sorted(name for name, entry in resources.items() if isinstance(entry, dict) and entry.get("assetType") == "font")
    if not 0 < len(names) <= MAX_FONTS:
        raise ValueError("font count limit")
    fonts = {}
    root = root.resolve(strict=True)
    for name in names:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", name):
            raise ValueError("unsafe font name")
        directory = (root / name).resolve(strict=True)
        if directory.parent != root:
            raise ValueError("font path escapes root")
        source = (directory / "LayoutData.lua").resolve(strict=True)
        atlas = (directory / (name + ".png")).resolve(strict=True)
        if source.parent != directory or atlas.parent != directory:
            raise ValueError("font resource escapes directory")
        if source.stat().st_size > MAX_SOURCE:
            raise ValueError("LayoutData size limit")
        fonts[name] = validate(LiteralParser(source.read_text(encoding="utf-8")).parse())
    return {"version": 1, "fonts": fonts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-root", type=pathlib.Path, required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    result = extract(args.font_root, args.manifest)
    args.output.write_bytes(plistlib.dumps(result, fmt=plistlib.FMT_BINARY, sort_keys=True))
    print(f"Extracted {len(result['fonts'])} fonts, {sum(len(f['characters']) for f in result['fonts'].values())} glyphs to {args.output}")


if __name__ == "__main__":
    main()
