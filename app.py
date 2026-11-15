import base64
import http.server
import json
import os
import re
import sys
import shutil
import socket
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

GRADES = {"sec2", "sec3", "y3", "y4"}   # السنة الثالثة والرابعة زي الثانوي
REVIEW_FOLDERS = {"reviews", "review", "ملازم", "مراجعات"}
EXAM_FOLDERS = {"exams", "exam", "اختبارات", "اختبار"}

os.makedirs(BOOKS_DIR, exist_ok=True)
os.makedirs(DATA_DIR, exist_ok=True)
state_lock = threading.Lock()


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
            ids = []
            for src in picked:
                name = os.path.basename(src)
                if name.lower().endswith(".pdf"):
                    shutil.copy2(src, os.path.join(target, name))
                    count += 1
                    # نفس المعرّف اللي scan_books بيرجّعه، عشان الكتاب لو كان متحذوف قبل كده يرجع يظهر
                    ids.append("/".join([grade.upper()] + ([folder] if folder else []) + [name]))
            return self.send_json({"ok": True, "count": count, "ids": ids})

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



def main():
    mode, srv = claim_single_instance()
    if mode == "secondary":
        return   # النسخة الأولى اتظهرت، والنسخة دي بتقفل
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

    if srv is not None:
        threading.Thread(target=watch_for_others, args=(srv, win), daemon=True).start()

    webview.start(_maximize, win)
    httpd.shutdown()


if __name__ == "__main__":
    main()
