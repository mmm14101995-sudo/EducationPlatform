import base64
import http.server
import json
import os
import re
import sys
import shutil
import subprocess
import threading
import urllib.parse
import urllib.request
import webbrowser

import webview

# ---------- المسارات ----------
IS_FROZEN = getattr(sys, "frozen", False)
APP_DIR = os.path.dirname(sys.executable) if IS_FROZEN else os.path.dirname(os.path.abspath(__file__))
BUNDLE_DIR = getattr(sys, "_MEIPASS", APP_DIR)

# index.html يُقرأ من مجلد البرنامج إن وُجد (لتعديله بسهولة)، وإلا من داخل الملف التنفيذي
UI_DIR = APP_DIR if os.path.exists(os.path.join(APP_DIR, "index.html")) else BUNDLE_DIR
BOOKS_DIR = os.path.join(APP_DIR, "books")
DATA_DIR = os.path.join(APP_DIR, "data")
EXPORT_DIR = os.path.join(APP_DIR, "exports")
STATE_FILE = os.path.join(DATA_DIR, "state.json")

# الواجهة تتحدث تلقائياً من GitHub: تعدّل index.html على GitHub مرة واحدة ويتطبق على الكمبيوتر
UI_URL = "https://raw.githubusercontent.com/mmm14101995-sudo/EducationPlatform/main/index.html"
UI_CACHE_DIR = os.path.join(DATA_DIR, "ui")
UI_CACHE = os.path.join(UI_CACHE_DIR, "index.html")

GRADES = {"sec2", "sec3"}
REVIEW_FOLDERS = {"reviews", "review", "ملازم", "مراجعات"}
EXAM_FOLDERS = {"exams", "exam", "اختبارات", "اختبار"}

# فيديوهات الكورسات: كل كورس مجلد اسمه معرّفه (حروف إنجليزية وأرقام فقط)
MEDIA_DIR = os.path.join(DATA_DIR, "videos")
COURSE_RE = re.compile(r"^[a-z0-9]{1,40}$")
VIDEO_EXT = (".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp")

os.makedirs(BOOKS_DIR, exist_ok=True)
os.makedirs(DATA_DIR, exist_ok=True)
state_lock = threading.Lock()


# ---------- فيديوهات الكورسات ----------
def course_dir(cid):
    if not COURSE_RE.match(cid or ""):
        return None
    return os.path.join(MEDIA_DIR, cid)


def list_media(cid):
    d = course_dir(cid)
    if not d or not os.path.isdir(d):
        return []
    out = []
    for name in sorted(os.listdir(d), key=str.lower):
        if os.path.isfile(os.path.join(d, name)) and name.lower().endswith(VIDEO_EXT):
            out.append({"name": name, "url": f"/media/{cid}/{urllib.parse.quote(name)}"})
    return out


# ---------- الكتب ----------
def scan_books():
    """يقرأ كل ملفات PDF داخل books/SEC2 و books/SEC3.
    أي PDF داخل مجلد اسمه reviews أو ملازم يُعتبر ملزمة، والباقي كتب."""
    items = []
    if not os.path.isdir(BOOKS_DIR):
        return items

    for grade_name in os.listdir(BOOKS_DIR):
        grade = grade_name.lower()
        grade_path = os.path.join(BOOKS_DIR, grade_name)
        if grade not in GRADES or not os.path.isdir(grade_path):
            continue

        for root, _dirs, files in os.walk(grade_path):
            sub = os.path.relpath(root, grade_path)
            sub_parts = [] if sub == "." else sub.split(os.sep)
            folders = [p.lower() for p in sub_parts]
            if any(f in EXAM_FOLDERS for f in folders):
                kind = "exams"
            elif any(f in REVIEW_FOLDERS for f in folders):
                kind = "reviews"
            else:
                kind = "books"

            for filename in files:
                if not filename.lower().endswith(".pdf"):
                    continue
                book_id = "/".join([grade_name, *sub_parts, filename])
                items.append({
                    "id": book_id,
                    "title": os.path.splitext(filename)[0],
                    "url": "/books/" + urllib.parse.quote(book_id),
                    "grade": grade,
                    "kind": kind,
                })

    items.sort(key=lambda b: b["title"].lower())
    return items


# ---------- الحفظ على القرص ----------
def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(data):
    with state_lock:
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp, STATE_FILE)


# ---------- الخادم المحلي ----------
def update_ui():
    """ينزّل أحدث index.html من GitHub. لو مفيش إنترنت يكمل بالنسخة المحفوظة أو المدمجة."""
    try:
        with urllib.request.urlopen(UI_URL, timeout=4) as r:
            data = r.read()
        if len(data) > 10000 and b"</html>" in data.lower():
            os.makedirs(UI_CACHE_DIR, exist_ok=True)
            tmp = UI_CACHE + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, UI_CACHE)
    except Exception:
        pass


LOCAL_UI = os.path.join(APP_DIR, "index.html")


def current_ui_dir():
    # الأولوية للنسخة اللي اتنزلت من GitHub (التحديث أونلاين).
    # لو مفيش نسخة محفوظة (أول تشغيل بدون نت) نستخدم index.html اللي جنب البرنامج، وبعدها اللي داخل الملف.
    if os.path.exists(UI_CACHE):
        return UI_CACHE_DIR
    if os.path.exists(LOCAL_UI):
        return APP_DIR
    return UI_DIR


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=current_ui_dir(), **kwargs)

    def log_message(self, format, *args):
        pass

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/media/list":
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            return self.send_json(list_media(query.get("course", [""])[0]))
        if path.startswith("/media/"):
            return self.serve_media(path)
        if path == "/api/books":
            return self.send_json(scan_books())
        if path == "/api/state":
            return self.send_json(load_state())
        if path.startswith("/books/"):
            return self.serve_book(urllib.parse.unquote(path[len("/books/"):]))
        if path in ("", "/"):
            self.path = "/index.html"
        return super().do_GET()

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        length = int(self.headers.get("Content-Length", 0) or 0)
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self.send_json({"ok": False}, 400)

        if path == "/api/state":
            save_state(payload)
            return self.send_json({"ok": True})

        if path == "/api/media/delete":
            d = course_dir(str(payload.get("course", "")))
            name = str(payload.get("name", ""))
            if not d or not name or os.path.basename(name) != name:
                return self.send_json({"ok": False}, 400)
            target = os.path.join(d, name)
            if os.path.isfile(target):
                os.remove(target)
            return self.send_json({"ok": True})

        if path == "/api/media/delete-course":
            d = course_dir(str(payload.get("course", "")))
            if not d:
                return self.send_json({"ok": False}, 400)
            shutil.rmtree(d, ignore_errors=True)
            return self.send_json({"ok": True})

        if path == "/api/books/pick":
            grade = str(payload.get("grade", "sec3")).lower()
            kind = str(payload.get("kind", "books"))
            if grade not in GRADES:
                return self.send_json({"ok": False}, 400)
            folder = {"books": "", "reviews": "reviews", "exams": "exams"}.get(kind)
            if folder is None:
                return self.send_json({"ok": False}, 400)
            wins = webview.windows
            if not wins:
                return self.send_json({"ok": False}, 500)
            picked = wins[0].create_file_dialog(
                webview.OPEN_DIALOG, allow_multiple=True, file_types=("PDF files (*.pdf)",)
            )
            if not picked:
                return self.send_json({"ok": False, "cancelled": True})
            target = os.path.join(BOOKS_DIR, grade.upper(), folder) if folder else os.path.join(BOOKS_DIR, grade.upper())
            os.makedirs(target, exist_ok=True)
            count = 0
            for src in picked:
                name = os.path.basename(src)
                if name.lower().endswith(".pdf"):
                    shutil.copy2(src, os.path.join(target, name))
                    count += 1
            return self.send_json({"ok": True, "count": count})

        if path == "/api/media/native-play":
            d = course_dir(str(payload.get("course", "")))
            name = str(payload.get("name", ""))
            if not d or not name or os.path.basename(name) != name:
                return self.send_json({"ok": False}, 400)
            target = os.path.join(d, name)
            if not os.path.isfile(target):
                return self.send_json({"ok": False}, 404)
            try:
                subprocess.Popen(
                    native_player_cmd(target, os.path.splitext(name)[0]),
                    creationflags=getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
                )
            except OSError:
                return self.send_json({"ok": False}, 500)
            return self.send_json({"ok": True})

        if path == "/api/media/pick":
            # يفتح نافذة اختيار الفيديوهات، وينسخها تلقائياً جوه مجلد الكورس
            d = course_dir(str(payload.get("course", "")))
            if not d:
                return self.send_json({"ok": False}, 400)
            wins = webview.windows
            if not wins:
                return self.send_json({"ok": False}, 500)
            picked = wins[0].create_file_dialog(
                webview.OPEN_DIALOG,
                allow_multiple=True,
                file_types=("Video files (*.mp4;*.webm;*.mkv;*.mov;*.m4v;*.3gp)",),
            )
            if not picked:
                return self.send_json({"ok": False, "cancelled": True})
            os.makedirs(d, exist_ok=True)
            count = 0
            for src in picked:
                name = os.path.basename(src)
                if name.lower().endswith(VIDEO_EXT):
                    shutil.copy2(src, os.path.join(d, name))
                    count += 1
            return self.send_json({"ok": True, "count": count})

        if path == "/api/media/open-folder":
            d = course_dir(str(payload.get("course", "")))
            if not d:
                return self.send_json({"ok": False}, 400)
            os.makedirs(d, exist_ok=True)
            if hasattr(os, "startfile"):
                os.startfile(d)
            return self.send_json({"ok": True, "path": d})

        if path == "/api/open-folder":
            grade = str(payload.get("grade", "sec3")).lower()
            if grade not in GRADES:
                return self.send_json({"ok": False}, 400)
            folder = os.path.join(BOOKS_DIR, grade.upper(), "exams")
            os.makedirs(folder, exist_ok=True)
            if hasattr(os, "startfile"):
                os.startfile(folder)  # يفتح المجلد في مستكشف الويندوز
            return self.send_json({"ok": True, "path": folder})

        if path == "/api/open-external":
            url = str(payload.get("url", ""))
            if url.startswith(("http://", "https://")):
                webbrowser.open(url)
            return self.send_json({"ok": True})

        if path == "/api/save-pdf":
            name = re.sub(r'[\\/:*?"<>|]', "_", str(payload.get("name", "board.pdf")))
            os.makedirs(EXPORT_DIR, exist_ok=True)
            out_path = os.path.join(EXPORT_DIR, name)
            with open(out_path, "wb") as f:
                f.write(base64.b64decode(payload.get("data", "")))
            return self.send_json({"ok": True, "path": out_path})

        self.send_error(404)

    def serve_media(self, path):
        """يخدم الفيديو مع دعم Range عشان التقديم والترجيع يشتغلوا."""
        parts = path[len("/media/"):].split("/", 1)
        if len(parts) != 2:
            return self.send_error(404)
        cid, name = urllib.parse.unquote(parts[0]), urllib.parse.unquote(parts[1])
        d = course_dir(cid)
        if not d or not name or os.path.basename(name) != name:
            return self.send_error(404)
        full = os.path.join(d, name)
        if not os.path.isfile(full):
            return self.send_error(404)

        size = os.path.getsize(full)
        start, end, status = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            spec = rng[len("bytes="):].split(",")[0].split("-")
            try:
                if spec[0]:
                    start = int(spec[0])
                else:
                    start = max(0, size - int(spec[1]))
                if len(spec) > 1 and spec[1]:
                    end = int(spec[1])
            except ValueError:
                return self.send_error(416)
            end = min(end, size - 1)
            if start > end or start >= size:
                return self.send_error(416)
            status = 206

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", "video/webm" if name.lower().endswith(".webm") else "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(full, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def serve_book(self, rel):
        base = os.path.abspath(BOOKS_DIR)
        full = os.path.abspath(os.path.join(base, rel))
        if not full.startswith(base + os.sep) or not os.path.isfile(full):
            return self.send_error(404)
        with open(full, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/pdf")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)



# ---------- المشغّل الأصلي للفيديو (libmpv + شريط تحكم) ----------
SPEEDS = ["0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "2.5x", "3x"]

def native_player_cmd(path, title):
    if IS_FROZEN:
        return [sys.executable, "--player", path, title]
    return [sys.executable, os.path.abspath(__file__), "--player", path, title]

def player_main(path, title):
    import time
    import tkinter as tk
    from tkinter import ttk, messagebox
    root = tk.Tk()
    root.title(title or "مشغّل الفيديو")
    root.geometry("1280x720")
    root.configure(bg="black")
    video = tk.Frame(root, bg="black")
    video.pack(fill="both", expand=True)
    root.update()
    try:
        import mpv
        player = mpv.MPV(
            wid=str(video.winfo_id()),
            keep_open=True,
            input_default_bindings=False,
            input_vo_keyboard=False,
            osc=False,
            ytdl=False,
            hr_seek="yes",
        )
        player.play(path)
    except Exception as e:
        messagebox.showerror("مشغّل الفيديو", "تعذر تشغيل الفيديو:\n" + str(e))
        root.destroy()
        return

    st = {"drag": False, "last": time.time(), "visible": True}
    seek_var = tk.DoubleVar(value=0)
    bar = tk.Frame(root, bg="#0f172a")
    bstyle = dict(bg="#1e293b", fg="white", activebackground="#334155", activeforeground="white",
                  bd=0, padx=12, pady=6, font=("Segoe UI", 11, "bold"), cursor="hand2")

    def fmt(t):
        t = max(0, int(t or 0))
        return "%d:%02d" % (t // 60, t % 60)

    def show_bar():
        st["last"] = time.time()
        if not st["visible"]:
            bar.place(relx=0, rely=1, anchor="sw", relwidth=1)
            st["visible"] = True

    def hide_bar():
        if st["visible"]:
            bar.place_forget()
            st["visible"] = False

    def toggle():
        player.pause = not player.pause
        show_bar()

    def rel(sec):
        player.seek(sec, reference="relative")
        show_bar()

    def on_speed(_=None):
        try:
            player.speed = float(speed.get().replace("x", ""))
        except ValueError:
            pass
        show_bar()

    def seek_abs(v):
        dur = player.duration or 0
        if dur:
            player.seek(dur * float(v) / 1000.0, reference="absolute")

    def toggle_full(_=None):
        on = not bool(root.attributes("-fullscreen"))
        root.attributes("-fullscreen", on)
        b_full.config(text="خروج من ملء الشاشة" if on else "ملء الشاشة")
        show_bar()

    def on_video_click(_=None):
        if not st["visible"]:
            show_bar()
        else:
            toggle()

    row1 = tk.Frame(bar, bg="#0f172a")
    row1.pack(fill="x", padx=14, pady=(10, 2))
    scale = ttk.Scale(row1, from_=0, to=1000, orient="horizontal", variable=seek_var)
    scale.pack(fill="x")
    scale.bind("<ButtonPress-1>", lambda e: st.update(drag=True))
    scale.bind("<ButtonRelease-1>", lambda e: (seek_abs(seek_var.get()), st.update(drag=False)))

    row2 = tk.Frame(bar, bg="#0f172a")
    row2.pack(fill="x", padx=14, pady=(2, 12))
    b_back = tk.Button(row2, text="−30 ث", command=lambda: rel(-30), **bstyle)
    b_play = tk.Button(row2, text="إيقاف", command=toggle, width=7, **bstyle)
    b_fwd = tk.Button(row2, text="+30 ث", command=lambda: rel(30), **bstyle)
    time_lbl = tk.Label(row2, text="0:00 / 0:00", fg="#e2e8f0", bg="#0f172a", font=("Segoe UI", 11))
    b_back.pack(side="left")
    b_play.pack(side="left", padx=6)
    b_fwd.pack(side="left")
    time_lbl.pack(side="left", padx=14)
    b_full = tk.Button(row2, text="ملء الشاشة", command=toggle_full, **bstyle)
    b_full.pack(side="right")
    speed = ttk.Combobox(row2, values=SPEEDS, state="readonly", width=6)
    speed.set("1x")
    speed.pack(side="right", padx=8)
    speed.bind("<<ComboboxSelected>>", on_speed)

    bar.place(relx=0, rely=1, anchor="sw", relwidth=1)
    video.bind("<Button-1>", on_video_click)
    root.bind_all("<Motion>", lambda e: show_bar())
    root.bind("<space>", lambda e: toggle())
    root.bind("<Left>", lambda e: rel(-30))
    root.bind("<Right>", lambda e: rel(30))
    root.bind("<f>", toggle_full)
    root.bind("<Escape>", lambda e: root.attributes("-fullscreen", False))

    def tick():
        try:
            pos = player.time_pos or 0
            dur = player.duration or 0
            time_lbl.config(text=fmt(pos) + " / " + fmt(dur))
            if dur and not st["drag"]:
                seek_var.set(pos / dur * 1000)
            b_play.config(text="تشغيل" if player.pause else "إيقاف")
            # الشريط يختفي بعد 10 ثواني لو الفيديو شغّال
            if st["visible"] and not player.pause and time.time() - st["last"] > 10:
                hide_bar()
        except Exception:
            pass
        root.after(250, tick)

    def close():
        try:
            player.terminate()
        except Exception:
            pass
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.after(250, tick)
    root.mainloop()

def main():
    update_ui()
    # منفذ عشوائي حر، فلا يتعارض مع أي برنامج آخر
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    win = webview.create_window(
        "المنصة التعليمية الشاملة",
        f"http://127.0.0.1:{port}/index.html",
        width=1320,
        height=860,
        min_size=(960, 620),
    )

    def _maximize(w):
        # يفتح التطبيق على كامل الشاشة (ملء الشاشة من غير ما يكون فيه وضع Fullscreen الكامل)
        try:
            w.maximize()
        except Exception:
            pass

    webview.start(_maximize, win)
    httpd.shutdown()


if __name__ == "__main__":
    if "--player" in sys.argv:
        i = sys.argv.index("--player")
        player_main(sys.argv[i + 1], sys.argv[i + 2] if len(sys.argv) > i + 2 else "")
    else:
        main()
