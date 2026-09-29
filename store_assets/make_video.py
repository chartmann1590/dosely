# Builds store_assets/video/dosely_promo.mp4 (1080x1920@30, ~50s).
# Scenes: intro card -> app screenshots (Ken Burns) -> outro end card with the
# feature graphic burned in. Captions burned in per scene; SAPI TTS narration
# (already rendered to seg0..seg5.wav) is aligned to scene starts.
import math
import os
import subprocess

ROOT = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(ROOT, "raw")
FR = os.path.join(ROOT, "video", "frames")
AU = os.path.join(ROOT, "video", "audio")
OUT = os.path.join(ROOT, "video", "dosely_promo.mp4")

FF = "ffmpeg"
FONT = r"C\:/Windows/Fonts/segoeuib.ttf"
W, H, FPS = 1080, 1920, 30

# measured narration durations (s)
SEG = [6.1745, 9.2345, 5.5445, 6.6845, 10.0645, 9.8545]
GAP = 0.4  # breathing room after each narration segment


def dur(i):
    return round(SEG[i] + GAP, 3)


def make_sbs():
    """Side-by-side composite for the insights scene (doses + calendar)."""
    from PIL import Image, ImageDraw
    a = Image.open(os.path.join(RAW, "02_doses.png")).convert("RGB")
    b = Image.open(os.path.join(RAW, "03_calendar.png")).convert("RGB")
    pw = 518
    ph = int(a.height * pw / a.width)
    a = a.resize((pw, ph), Image.LANCZOS)
    b = b.resize((pw, ph), Image.LANCZOS)
    img = Image.new("RGB", (W, H), (10, 44, 34))
    gap = (W - 2 * pw) // 3
    y = (H - ph) // 2 + 60
    img.paste(a, (gap, y))
    img.paste(b, (gap * 2 + pw, y))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((gap - 4, y - 4, gap + pw + 4, y + ph + 4), radius=28, outline=(62, 207, 116), width=3)
    d.rounded_rectangle((gap * 2 + pw - 4, y - 4, gap * 2 + 2 * pw + 4, y + ph + 4), radius=28, outline=(62, 207, 116), width=3)
    img.save(os.path.join(FR, "sbs.png"))
    print("sbs composite ->", os.path.join(FR, "sbs.png"))


def frame_path(name):
    """intro/outro/sbs are generated into frames/; app shots live in raw/."""
    if name.startswith("raw:"):
        return os.path.join(RAW, name[4:])
    return os.path.join(FR, name)


# scene list: (image, duration, [(text, size, y), ...])
SCENES = [
    ("intro.png", dur(0), [("Meet Dosely", 96, 1560), ("Your private GLP-1 companion", 46, 1690)]),
    ("raw:01_home.png", dur(1), [("Log injections in seconds", 66, 1740)]),
    ("sbs.png", dur(2), [("Insights that keep you going", 60, 1740)]),
    ("raw:04_weight.png", dur(3), [("kg or lb, your choice", 66, 1740)]),
    ("raw:05_coach.png", dur(4), [("On-device AI coach", 72, 1690), ("Private. Offline. Not a doctor.", 44, 1800)]),
    ("raw:06_settings.png", 4.0, [("59 languages. Built-in reminders.", 56, 1740)]),
    ("outro.png", 6.5, []),
]


def caption_filter(text, size, y, total):
    fade = f"if(lt(t,0.4),t/0.4,if(gt(t,{total - 0.4}),({total}-t)/0.4,1))"
    return (f"drawtext=fontfile='{FONT}':text='{text}':fontsize={size}:"
            f"fontcolor=0xF7F5EF:box=1:boxcolor=0x0A2C22@0.62:boxborderw=24:"
            f"x=(w-text_w)/2:y={y}:alpha='{fade}'")


def render_scene(idx, image, total, texts):
    outp = os.path.join(FR, f"scene{idx}.mp4")
    frames = math.ceil(total * FPS)
    vf = [
        f"scale=1080:1920:force_original_aspect_ratio=increase",
        f"crop=1080:1920",
        f"zoompan=z='min(1+0.07*on/{frames},1.07)':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s={W}x{H}:fps={FPS}",
    ] + [caption_filter(*t, total) for t in texts]
    cmd = [FF, "-y", "-loop", "1", "-framerate", str(FPS), "-t", f"{total}",
           "-i", frame_path(image),
           "-vf", ",".join(vf),
           "-frames:v", str(frames),
           "-c:v", "libx264", "-preset", "medium", "-crf", "19",
           "-pix_fmt", "yuv420p", "-r", str(FPS), outp]
    subprocess.run(cmd, check=True, capture_output=True)
    print(f"scene{idx} ({image}, {total}s) ->", outp)


def concat():
    lst = os.path.join(FR, "concat.txt")
    with open(lst, "w") as f:
        for i in range(len(SCENES)):
            f.write(f"file 'scene{i}.mp4'\n")
    part = os.path.join(FR, "video_only.mp4")
    subprocess.run([FF, "-y", "-f", "concat", "-safe", "0", "-i", lst,
                    "-c", "copy", part], check=True, capture_output=True)
    print("concat ->", part)
    return part


def scene_starts():
    starts, t = [], 0.0
    for _, d, _ in SCENES:
        starts.append(round(t, 3))
        t += d
    return starts, t


def mix_audio(total):
    starts, _ = scene_starts()
    # narration segment i starts at the beginning of scene i
    inputs, filters, labels = [], [], []
    for i in range(6):
        inputs += ["-i", os.path.join(AU, f"seg{i}.wav")]
        delay = int(starts[i] * 1000)
        filters.append(f"[{i}:a]adelay={delay}|{delay}[a{i}]")
        labels.append(f"[a{i}]")
    filters.append(f"{''.join(labels)}amix=inputs=6:normalize=0[mix]")
    filters.append(f"[mix]atrim=0:{total},asetpts=PTS-STARTPTS,"
                   f"afade=t=in:st=0:d=0.5,afade=t=out:st={total - 1.4}:d=1.4[out]")
    silent_len = total
    cmd = [FF, "-y",
           "-f", "lavfi", "-t", f"{silent_len}", "-i", "anullsrc=r=48000:cl=mono"] + inputs + \
          ["-filter_complex", ";".join(filters),
           "-map", "[out]", "-c:a", "aac", "-b:a", "160k", "-ar", "48000",
           os.path.join(FR, "narration.m4a")]
    subprocess.run(cmd, check=True, capture_output=True)
    print("narration ->", os.path.join(FR, "narration.m4a"))


def mux(part, total):
    subprocess.run([FF, "-y", "-i", part, "-i", os.path.join(FR, "narration.m4a"),
                    "-map", "0:v", "-map", "1:a",
                    "-c:v", "copy", "-c:a", "copy",
                    "-movflags", "+faststart",
                    "-t", f"{total}", OUT], check=True, capture_output=True)
    print("FINAL ->", OUT)


if __name__ == "__main__":
    make_sbs()
    for i, (img, d, texts) in enumerate(SCENES):
        render_scene(i, img, d, texts)
    part = concat()
    _, total = scene_starts()
    mix_audio(total)
    mux(part, total)
    print(f"total duration: {total:.2f}s")
