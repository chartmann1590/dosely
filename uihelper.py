import re
import subprocess
import sys
import time

SERIAL = "37220DLJG001ML"


def adb(*args):
    return subprocess.run(
        ["adb", "-s", SERIAL, *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )


def dump(path="ui.xml"):
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", path)
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def nodes(xml):
    return re.findall(r"<node [^>]*>", xml)


def attrs(node):
    return dict(re.findall(r'(\w[\w-]*)="([^"]*)"', node))


def find_bounds(xml, text=None, desc=None, contains=False):
    for n in nodes(xml):
        a = attrs(n)
        hay_t = a.get("text", "")
        hay_d = a.get("content-desc", "")
        if text is not None:
            hay = hay_t
            ok = (text in hay) if contains else (hay == text)
            if ok:
                return a.get("bounds")
        if desc is not None:
            hay = hay_d
            ok = (desc in hay) if contains else (hay == desc)
            if ok:
                return a.get("bounds")
    return None


def tap_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    adb("shell", "input", "tap", str(cx), str(cy))
    return True


def tap_text(text, wait=1.2, contains=False):
    xml = dump()
    b = find_bounds(xml, text=text, contains=contains)
    if not b:
        print(f"NOT FOUND: {text!r}")
        return False
    ok = tap_bounds(b)
    time.sleep(wait)
    print(f"TAPPED {text!r}")
    return ok


def texts_on_screen():
    xml = dump()
    out = sorted(set(re.findall(r'text="([^"]{2,})"', xml)))
    print("\n".join(out))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "texts":
        texts_on_screen()
    elif cmd == "tap":
        tap_text(sys.argv[2], contains="--contains" in sys.argv)
    elif cmd == "tapd":
        xml = dump()
        b = find_bounds(xml, desc=sys.argv[2])
        print("FOUND" if tap_bounds(b) else "NOT FOUND")
