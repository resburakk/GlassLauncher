# Cam 27 simge paketi: her simge için arka plan + ön plan SVG (108x108, uyarlanabilir simge ölçüsü).
import json, os, sys
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

def mat(path, scale=2.0, cx=54, cy=54, fill="#fff"):
    # Material sembolü (24x24) merkeze ölçeklenir.
    o = 12 * scale
    return f'<path transform="translate({cx-o},{cy-o}) scale({scale})" d="{path}" fill="{fill}"/>'

P = {
 "call": "M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z",
 "person": "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
 "mail": "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z",
 "video": "M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z",
 "note": "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z",
 "folder": "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
 "pin": "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
 "shield": "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z",
 "heart": "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
 "bulb": "M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7z",
 "palette": "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
 "refresh": "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
 "hourglass": "M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99-.01-.01H18V2H6z",
 "translate": "M12.87 15.07l-2.54-2.51.03-.03c1.74-1.94 2.98-4.17 3.71-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12zm-2.62 7l1.62-4.33L19.12 17h-3.24z",
 "photo": "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
 "globe": "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z",
}

def gear(fill="#fff", hole="#8E8E93"):
    teeth = "".join(f'<rect x="50.2" y="27" width="7.6" height="11" rx="2" transform="rotate({a} 54 54)" fill="{fill}"/>' for a in range(0, 360, 30))
    return teeth + f'<circle cx="54" cy="54" r="19" fill="{fill}"/><circle cx="54" cy="54" r="8.5" fill="{hole}"/>' \
        + f'<circle cx="54" cy="54" r="14.5" fill="none" stroke="{hole}" stroke-width="1.6" opacity=".55"/>'

def clock():
    ticks = "".join(f'<rect x="53.2" y="{31 if a%90==0 else 32}" width="1.6" height="{5 if a%90==0 else 3}" rx=".8" fill="#1C1C1E" transform="rotate({a} 54 54)"/>' for a in range(0, 360, 30))
    return f'<circle cx="54" cy="54" r="26" fill="#fff"/>{ticks}' \
        '<line x1="54" y1="54" x2="54" y2="40" stroke="#1C1C1E" stroke-width="3" stroke-linecap="round"/>' \
        '<line x1="54" y1="54" x2="66" y2="58" stroke="#1C1C1E" stroke-width="2.4" stroke-linecap="round"/>' \
        '<line x1="54" y1="60" x2="54" y2="33" stroke="#FF9F0A" stroke-width="1.2" stroke-linecap="round"/>' \
        '<circle cx="54" cy="54" r="2.2" fill="#FF9F0A"/>'

def calendar():
    dots = "".join(f'<circle cx="{40+c*9.3}" cy="{55+r*8}" r="2.3" fill="{"#FF3B30" if (r,c)==(1,2) else "#3A3A3C"}"/>' for r in range(3) for c in range(4))
    return '<rect x="30" y="31" width="48" height="46" rx="8" fill="#fff"/>' \
           '<path d="M30 39a8 8 0 0 1 8-8h32a8 8 0 0 1 8 8v4H30z" fill="#FF3B30"/>' + dots

def calculator():
    keys = ""
    for r in range(3):
        for c in range(3):
            col = "#FF9F0A" if c == 2 else "#D1D1D6"
            keys += f'<rect x="{35+c*13.5}" y="{47+r*10.5}" width="10.5" height="8" rx="4" fill="{col}"/>'
    return '<rect x="33" y="31" width="42" height="11" rx="3" fill="#fff" opacity=".92"/>' + keys

def notes():
    lines = "".join(f'<rect x="38" y="{48+i*7}" width="{32 if i<3 else 20}" height="2.2" rx="1.1" fill="#C7A300" opacity=".75"/>' for i in range(4))
    return '<rect x="31" y="29" width="46" height="50" rx="7" fill="#fff"/><path d="M31 36a7 7 0 0 1 7-7h32a7 7 0 0 1 7 7v4H31z" fill="#FFD60A"/>' + lines

def recorder():
    hs = [6, 12, 20, 28, 18, 30, 22, 12, 16, 8]
    return "".join(f'<rect x="{31+i*5}" y="{54-h/2}" width="3" height="{h}" rx="1.5" fill="#FF453A"/>' for i, h in enumerate(hs))

def compass():
    return '<circle cx="54" cy="54" r="26" fill="none" stroke="#fff" stroke-width="2.5"/>' \
        + "".join(f'<rect x="53.2" y="30.5" width="1.6" height="4" rx=".8" fill="#fff" transform="rotate({a} 54 54)"/>' for a in range(0, 360, 45)) \
        + '<path d="M54 34l6 20h-12z" fill="#FF453A"/><path d="M54 74l-6-20h12z" fill="#fff"/><circle cx="54" cy="54" r="2.5" fill="#1C1C1E"/>'

def mirror():
    return '<ellipse cx="54" cy="52" rx="17" ry="23" fill="#fff" opacity=".95"/><ellipse cx="54" cy="52" rx="13.5" ry="19.5" fill="url(#mir)"/>' \
           '<path d="M45 42 l9 -6 M47 50 l13 -9" stroke="#fff" stroke-width="2.4" stroke-linecap="round" opacity=".8"/>' \
           '<rect x="51" y="75" width="6" height="5" rx="2" fill="#fff"/>'

def weather():
    rays = "".join(f'<rect x="61" y="28" width="2.4" height="5" rx="1.2" fill="#FFD60A" transform="rotate({a} 62.2 43)"/>' for a in range(0, 360, 45))
    return rays + '<circle cx="62.2" cy="43" r="9.5" fill="#FFD60A"/>' \
        '<path d="M38 72h28a9 9 0 0 0 0-18 12 12 0 0 0-23-3 8.5 8.5 0 0 0-5 21z" fill="#fff"/>'

def camera():
    return '<rect x="29" y="38" width="50" height="34" rx="8" fill="#3A3A3C"/><rect x="44" y="33" width="20" height="8" rx="3" fill="#3A3A3C"/>' \
        '<circle cx="54" cy="55" r="12" fill="#1C1C1E"/><circle cx="54" cy="55" r="8.5" fill="url(#lens)"/><circle cx="51" cy="52" r="2.2" fill="#fff" opacity=".8"/>' \
        '<circle cx="71" cy="45" r="2" fill="#FFD60A"/>'

def bubble():
    return '<path d="M54 30c-14.4 0-26 9.4-26 21 0 6.7 3.9 12.7 10 16.5-.6 3.3-2.5 6.3-5 8.5 4.8-.2 9.2-1.9 12.5-4.7 2.7.8 5.5 1.2 8.5 1.2 14.4 0 26-9.4 26-21.5S68.4 30 54 30z" fill="#fff"/>'

def play():
    return '<path d="M46 34.5c0-2 2.2-3.2 3.9-2.1l23.5 15.4c1.5 1 1.5 3.2 0 4.2L49.9 67.4c-1.7 1.1-3.9-.1-3.9-2.1z" fill="#fff" transform="translate(-3 3)"/>'

def bag():
    return '<path d="M42 41v-3a12 12 0 0 1 24 0v3" fill="none" stroke="#fff" stroke-width="3.2" stroke-linecap="round"/>' \
           '<path d="M33 41h42l-3 32a5 5 0 0 1-5 4.5H41a5 5 0 0 1-5-4.5z" fill="#fff"/>'

def wallet():
    return '<rect x="31" y="35" width="46" height="13" rx="4" fill="#FF9F0A"/><rect x="31" y="41" width="46" height="13" rx="4" fill="#30D158"/>' \
           '<rect x="31" y="47" width="46" height="13" rx="4" fill="#0A84FF"/><rect x="29" y="52" width="50" height="25" rx="6" fill="#E5E5EA"/>'

def remote():
    return '<rect x="43" y="27" width="22" height="54" rx="10" fill="#fff"/><circle cx="54" cy="40" r="6" fill="#1C1C1E"/><circle cx="54" cy="40" r="2" fill="#fff"/>' \
           + "".join(f'<circle cx="{49+c*10}" cy="{56+r*8}" r="2.4" fill="#636366"/>' for r in range(3) for c in range(2))

# ad: (arka plan üst, alt, ön plan svg)
ICONS = {
 "cam_phone":      ("#5BE37D", "#1DB954", mat(P["call"], 2.1)),
 "cam_contacts":   ("#B9BEC6", "#8A9099", mat(P["person"], 2.3)),
 "cam_messages":   ("#5AC8FA", "#0A84FF", bubble()),
 "cam_mail":       ("#4DA3FF", "#1460D9", mat(P["mail"], 2.0)),
 "cam_videocall":  ("#4CD9A0", "#0F9E6E", mat(P["video"], 2.0)),
 "cam_settings":   ("#C7C7CC", "#8E8E93", gear()),
 "cam_clock":      ("#2C2C2E", "#000000", clock()),
 "cam_calendar":   ("#F2F2F7", "#D8D8DE", calendar()),
 "cam_calculator": ("#3A3A3C", "#1C1C1E", calculator()),
 "cam_notes":      ("#FFF6C7", "#FFE27A", notes()),
 "cam_recorder":   ("#2C2C2E", "#0B0B0C", recorder()),
 "cam_compass":    ("#2C2C2E", "#0B0B0C", compass()),
 "cam_mirror":     ("#D7DCE3", "#9AA3AF", mirror()),
 "cam_weather":    ("#58A6F5", "#1E66D0", weather()),
 "cam_camera":     ("#E5E5EA", "#AEAEB2", camera()),
 "cam_photos":     ("#FF7AA8", "#9B5CFF", mat(P["photo"], 1.9)),
 "cam_music":      ("#FF6B81", "#E5214F", mat(P["note"], 2.1)),
 "cam_video":      ("#7B6CFF", "#3F2FD6", play()),
 "cam_files":      ("#5AB0FF", "#1F74E8", mat(P["folder"], 2.0)),
 "cam_browser":    ("#4DA3FF", "#1460D9", mat(P["globe"], 2.1)),
 "cam_maps":       ("#7EE08B", "#2FB15A", mat(P["pin"], 2.1)),
 "cam_translate":  ("#5B8CFF", "#2B54D6", mat(P["translate"], 1.9)),
 "cam_store":      ("#3FC7FF", "#1270E6", bag()),
 "cam_security":   ("#5BE37D", "#1F9E4A", mat(P["shield"], 2.0)),
 "cam_update":     ("#8E8E93", "#5A5A60", mat(P["refresh"], 2.1)),
 "cam_themes":     ("#FFB347", "#FF6A2B", mat(P["palette"], 2.0)),
 "cam_tips":       ("#FFE066", "#FFB800", mat(P["bulb"], 2.0, fill="#fff")),
 "cam_health":     ("#FFFFFF", "#ECECF1", mat(P["heart"], 1.9, fill="#FF375F")),
 "cam_wellbeing":  ("#4FD1C5", "#1E9A8F", mat(P["hourglass"], 1.8)),
 "cam_remote":     ("#3A3A3C", "#1C1C1E", remote()),
 "cam_wallet":     ("#2C2C2E", "#000000", wallet()),
}

DEFS = '<defs><radialGradient id="lens"><stop offset="0" stop-color="#5E9BFF"/><stop offset="1" stop-color="#14204A"/></radialGradient>' \
       '<linearGradient id="mir" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#EAF1FB"/><stop offset="1" stop-color="#9DB2CC"/></linearGradient></defs>'

SCALE = {"cam_settings": .82, "cam_clock": .88, "cam_compass": .88, "cam_messages": .84, "cam_store": .84,
         "cam_remote": .84, "cam_wallet": .9, "cam_calendar": .94, "cam_notes": .94, "cam_camera": .92, "cam_weather": .94}
for name, (top, bot, fg) in ICONS.items():
    k = SCALE.get(name)
    if k: fg = f'<g transform="translate({54*(1-k)} {54*(1-k)}) scale({k})">{fg}</g>'
    bg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">' \
         f'<stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bot}"/></linearGradient>' \
         f'<radialGradient id="h" cx=".5" cy="0" r=".75"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>' \
         f'<rect width="108" height="108" fill="url(#g)"/><rect width="108" height="108" fill="url(#h)"/></svg>'
    fgs = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">{DEFS}{fg}</svg>'
    open(f"{OUT}/{name}_bg.svg", "w").write(bg)
    open(f"{OUT}/{name}_fg.svg", "w").write(fgs)
json.dump(list(ICONS), open(f"{OUT}/names.json", "w"))
print(len(ICONS))
