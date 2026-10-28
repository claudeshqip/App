"""Shkarko për Windows: dritare me WebView2 mbi serverin lokal Flask (app.py).

Ekzekutohet si Shkarko.exe (PyInstaller). ffmpeg dhe deno janë në dosjen bin/
pranë programit. yt-dlp përditësohet vetë: versioni i ri shkarkohet në sfond
dhe përdoret herën tjetër që hapet app-i.
"""

import importlib.machinery
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.request

DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~/.config"), "Shkarko")

# Në exe-në pa konsolë sys.stdout/stderr janë None; yt-dlp dhe Flask duan ku të shkruajnë.
if sys.stdout is None or sys.stderr is None:
    os.makedirs(DATA_DIR, exist_ok=True)
    _log = open(os.path.join(DATA_DIR, "shkarko.log"), "w", encoding="utf-8", buffering=1)  # noqa: SIM115
    sys.stdout = sys.stdout or _log
    sys.stderr = sys.stderr or _log

RESOURCES = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
os.environ["PATH"] = os.path.join(RESOURCES, "bin") + os.pathsep + os.environ.get("PATH", "")
os.environ["SHKARKO_DESKTOP"] = "1"
os.environ.setdefault("SHKARKO_VERSION", open(os.path.join(RESOURCES, "VERSION")).read().strip()
                      if os.path.exists(os.path.join(RESOURCES, "VERSION")) else "dev")

YTDLP_DIR = os.path.join(DATA_DIR, "yt-dlp")
YTDLP_ZIP = os.path.join(YTDLP_DIR, "yt-dlp.zip")
YTDLP_VERSION = os.path.join(YTDLP_DIR, "version.txt")
PORT = 47821  # port i fiksuar, që cilësimet në localStorage të mbahen mend


def version_tuple(text):
    return tuple(int(p) for p in text.strip().split(".") if p.isdigit())


class _YtDlpFromZip:
    """Bën që `import yt_dlp` të merret nga yt-dlp.zip i përditësuar, jo nga ai brenda exe-së."""

    def __init__(self, path):
        self.path = path

    def find_spec(self, name, path=None, target=None):
        if name.split(".")[0] not in ("yt_dlp", "yt_dlp_ejs"):
            return None
        return importlib.machinery.PathFinder.find_spec(name, path or [self.path])


def use_updated_ytdlp():
    try:
        with open(YTDLP_VERSION) as fh:
            downloaded = fh.read().strip()
    except OSError:
        return
    import yt_dlp.version as bundled  # noqa: PLC0415

    if version_tuple(downloaded) <= version_tuple(bundled.__version__):
        return
    for name in [m for m in sys.modules if m.split(".")[0] == "yt_dlp"]:
        del sys.modules[name]
    finder = _YtDlpFromZip(YTDLP_ZIP)
    sys.meta_path.insert(0, finder)
    try:
        import yt_dlp  # noqa: F401, PLC0415
    except Exception:  # noqa: BLE001 - zip-i i prishur: kthehemi te versioni i brendshëm
        sys.meta_path.remove(finder)
        for name in [m for m in sys.modules if m.split(".")[0] == "yt_dlp"]:
            del sys.modules[name]


def update_ytdlp_in_background(current_version):
    def run():
        try:
            req = urllib.request.Request(
                "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest",
                headers={"User-Agent": "Shkarko"},
            )
            with urllib.request.urlopen(req, timeout=20) as res:
                tag = json.load(res)["tag_name"]
            if version_tuple(tag) <= version_tuple(current_version):
                return
            os.makedirs(YTDLP_DIR, exist_ok=True)
            tmp = YTDLP_ZIP + ".part"
            url = f"https://github.com/yt-dlp/yt-dlp/releases/download/{tag}/yt-dlp"
            urllib.request.urlretrieve(url, tmp)  # noqa: S310
            os.replace(tmp, YTDLP_ZIP)
            with open(YTDLP_VERSION, "w") as fh:
                fh.write(tag)
        except Exception:  # noqa: BLE001 - pa internet: provohet herën tjetër
            pass

    threading.Thread(target=run, daemon=True).start()


def start_server(flask_app):
    from werkzeug.serving import make_server  # noqa: PLC0415

    try:
        server = make_server("127.0.0.1", PORT, flask_app, threaded=True)
    except OSError:
        server = make_server("127.0.0.1", 0, flask_app, threaded=True)  # porti i zënë
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server.server_port


def selftest(log_path):
    """Kontroll i shpejtë në CI: a hapet serveri, a gjenden ffmpeg/deno, a lexohet një video."""
    lines, ok = [], True

    def log(text):
        lines.append(text)
        with open(log_path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")

    import app  # noqa: PLC0415

    port = start_server(app.app)
    base = f"http://127.0.0.1:{port}"
    try:
        with urllib.request.urlopen(base + "/api/platform", timeout=10) as res:
            log("platform: " + res.read().decode())
        with urllib.request.urlopen(base + "/?desktop", timeout=10) as res:
            log(f"index: {res.status}")
    except Exception as exc:  # noqa: BLE001
        log(f"FAIL server: {exc}")
        ok = False

    for tool in (["ffmpeg", "-version"], ["deno", "--version"]):
        try:
            out = subprocess.run(tool, capture_output=True, text=True, timeout=60, check=True).stdout
            log(f"{tool[0]}: {out.splitlines()[0]}")
        except Exception as exc:  # noqa: BLE001
            log(f"FAIL {tool[0]}: {exc}")
            ok = False

    try:
        import yt_dlp_ejs  # noqa: F401, PLC0415

        log("yt_dlp_ejs: ok")
    except ImportError as exc:
        log(f"FAIL yt_dlp_ejs: {exc}")
        ok = False

    # YouTube mund t'i bllokojë serverat e GitHub-it, prandaj kjo pjesë vetëm raportohet.
    try:
        url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        with urllib.request.urlopen(f"{base}/api/info?url={url}", timeout=120) as res:
            data = json.load(res)
        log("youtube info: " + ", ".join(f"{r['height']}p" for r in data["resolutions"]))
        body = json.dumps({"url": url, "mode": "video", "height": data["resolutions"][-1]["height"],
                           "container": "mp4", "title": data["title"]}).encode()
        req = urllib.request.Request(f"{base}/api/download", body, {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as res:
            job_id = json.load(res)["job_id"]
        for _ in range(120):
            with urllib.request.urlopen(f"{base}/api/progress/{job_id}", timeout=10) as res:
                job = json.load(res)
            if job["status"] in ("finished", "error"):
                break
            time.sleep(1)
        log(f"youtube download: {job['status']} {job.get('filename') or job.get('error')}")
    except Exception as exc:  # noqa: BLE001
        log(f"youtube (jo fatale): {exc}")

    log("RESULT: " + ("OK" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    use_updated_ytdlp()
    if len(sys.argv) >= 3 and sys.argv[1] == "--selftest":
        sys.exit(selftest(sys.argv[2]))

    import webview  # noqa: PLC0415

    import app  # noqa: PLC0415

    update_ytdlp_in_background(app.yt_dlp.version.__version__)
    port = start_server(app.app)
    window = webview.create_window(
        "Shkarko",
        f"http://127.0.0.1:{port}/?desktop",
        width=1000,
        height=860,
        min_size=(420, 600),
    )

    folder_dialog = getattr(getattr(webview, "FileDialog", None), "FOLDER", None)
    if folder_dialog is None:
        folder_dialog = webview.FOLDER_DIALOG

    def choose_folder(current):
        result = window.create_file_dialog(folder_dialog, directory=current)
        return result[0] if result else None

    app.choose_folder_dialog = choose_folder
    webview.start(private_mode=False, storage_path=os.path.join(DATA_DIR, "webview"))


if __name__ == "__main__":
    main()
