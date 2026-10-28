"""Shkarko - web app i thjeshtë mbi Flask + yt-dlp.

I njëjti server përdoret edhe nga app-i për Windows (desktop.py). Në atë
rast (SHKARKO_DESKTOP=1) skedarët ruhen direkt në një dosje të kompjuterit
dhe ka edhe listë shkarkimesh e cilësime, njësoj si në app-in Android.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

import yt_dlp
from flask import Flask, abort, jsonify, render_template, request, send_file

app = Flask(__name__)

VIDEO_CONTAINERS = ("mp4", "mkv", "webm")
AUDIO_FORMATS = ("mp3", "m4a", "opus", "wav", "flac")
AUDIO_BITRATES = ("128", "192", "256", "320")
JOB_TTL_SECONDS = 60 * 60  # skedarët fshihen pas 1 ore

YOUTUBE_RE = re.compile(r"^https?://([a-z0-9-]+\.)?(youtube\.com|youtu\.be)/", re.I)

jobs = {}
jobs_lock = threading.Lock()

DESKTOP = os.environ.get("SHKARKO_DESKTOP") == "1"
APP_VERSION = os.environ.get("SHKARKO_VERSION", "dev")
DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~/.config"), "Shkarko")
DEFAULT_FOLDER = os.path.join(os.path.expanduser("~"), "Downloads", "Shkarko")
data_lock = threading.Lock()

# desktop.py e vendos këtë: hap dritaren e Windows-it për zgjedhjen e dosjes.
choose_folder_dialog = None


def base_opts():
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "noprogress": True,
    }
    # YouTube kërkon një JavaScript runtime për t'i zbërthyer të gjitha formatet.
    runtimes = {name: {} for name in ("deno", "node", "bun") if shutil.which(name)}
    if runtimes:
        opts["js_runtimes"] = runtimes
    return opts


def resolution_label(height):
    names = {4320: "8K", 2160: "4K", 1440: "2K", 1080: "Full HD", 720: "HD"}
    return names.get(height, "")


def validate_url(url):
    if not url or not YOUTUBE_RE.match(url):
        abort(400, description="Ju lutem shkruani një link valid të YouTube.")
    return url


# ---- Ruajtja e të dhënave (vetëm në desktop) ----

def _load(name, fallback):
    try:
        with open(os.path.join(DATA_DIR, name), encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return fallback


def _save(name, value):
    os.makedirs(DATA_DIR, exist_ok=True)
    path = os.path.join(DATA_DIR, name)
    with open(path + ".tmp", "w", encoding="utf-8") as fh:
        json.dump(value, fh, ensure_ascii=False)
    os.replace(path + ".tmp", path)


def download_folder():
    folder = _load("settings.json", {}).get("folder")
    return folder if folder and os.path.isdir(folder) else DEFAULT_FOLDER


def unique_path(folder, name):
    base, ext = os.path.splitext(name)
    path, n = os.path.join(folder, name), 1
    while os.path.exists(path):
        path = os.path.join(folder, f"{base} ({n}){ext}")
        n += 1
    return path


def save_to_folder(filepath, entry):
    """Zhvendos skedarin e gatshëm në dosjen e zgjedhur dhe e shton te Shkarkimet."""
    folder = download_folder()
    os.makedirs(folder, exist_ok=True)
    dest = unique_path(folder, os.path.basename(filepath))
    size = os.path.getsize(filepath)
    shutil.move(filepath, dest)
    with data_lock:
        history = _load("history.json", [])
        history.insert(0, {**entry, "filename": os.path.basename(dest), "path": dest, "size": size,
                           "date": int(time.time() * 1000)})
        _save("history.json", history[:500])
    return dest, folder


def desktop_only():
    if not DESKTOP:
        abort(404)


@app.errorhandler(400)
def bad_request(err):
    return jsonify(error=err.description), 400


@app.get("/")
def index():
    # index.html është HTML statik, që të përdoret njësoj edhe në app-in Android.
    return render_template("index.html")


@app.get("/api/info")
def info():
    url = validate_url(request.args.get("url", "").strip())
    try:
        with yt_dlp.YoutubeDL(base_opts()) as ydl:
            data = ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as exc:
        return jsonify(error=f"Videoja nuk u gjet ose nuk mund të lexohet: {exc}"), 422

    formats = data.get("formats") or []
    audio_sizes = [
        f.get("filesize") or f.get("filesize_approx") or 0
        for f in formats
        if f.get("vcodec") == "none" and f.get("acodec") not in (None, "none")
    ]
    best_audio_size = max(audio_sizes, default=0)

    by_height = {}
    for f in formats:
        height = f.get("height")
        if not height or f.get("vcodec") in (None, "none"):
            continue
        entry = by_height.setdefault(height, {"height": height, "fps": 0, "size": 0, "codecs": set()})
        entry["fps"] = max(entry["fps"], int(f.get("fps") or 0))
        size = f.get("filesize") or f.get("filesize_approx") or 0
        if f.get("acodec") in (None, "none"):
            size += best_audio_size
        entry["size"] = max(entry["size"], size)
        entry["codecs"].add((f.get("vcodec") or "").split(".")[0])

    resolutions = []
    for height in sorted(by_height, reverse=True):
        entry = by_height[height]
        entry["codecs"] = sorted(c for c in entry["codecs"] if c)
        entry["label"] = resolution_label(height)
        resolutions.append(entry)

    return jsonify(
        id=data.get("id"),
        title=data.get("title"),
        uploader=data.get("uploader"),
        duration=data.get("duration"),
        thumbnail=data.get("thumbnail"),
        resolutions=resolutions,
    )


def build_download_opts(job_dir, mode, height, container, bitrate):
    opts = base_opts()
    opts["outtmpl"] = os.path.join(job_dir, "%(title).150B [%(id)s].%(ext)s")

    if mode == "audio":
        opts["format"] = "bestaudio/best"
        pp = {"key": "FFmpegExtractAudio", "preferredcodec": container}
        if container == "mp3":
            pp["preferredquality"] = bitrate
        opts["postprocessors"] = [pp]
        return opts

    h = int(height)
    opts["format"] = (
        f"bv*[height={h}]+ba/b[height={h}]"
        f"/bv*[height<={h}]+ba/b[height<={h}]/bv*+ba/b"
    )
    if container == "mp4":
        # Preferohen rrjedhat mp4/m4a, që të mos duhet ri-kodim.
        opts["format_sort"] = ["res", "fps", "ext:mp4:m4a"]
        opts["merge_output_format"] = "mp4"
        opts["postprocessors"] = [{"key": "FFmpegVideoRemuxer", "preferedformat": "mp4"}]
    elif container == "webm":
        opts["format_sort"] = ["res", "fps", "ext:webm:webm"]
        opts["merge_output_format"] = "webm/mkv"
        # Nëse kodekët nuk futen dot në webm, konvertohet.
        opts["postprocessors"] = [{"key": "FFmpegVideoConvertor", "preferedformat": "webm"}]
    else:
        opts["format_sort"] = ["res", "fps"]
        opts["merge_output_format"] = "mkv"
        opts["postprocessors"] = [{"key": "FFmpegVideoRemuxer", "preferedformat": "mkv"}]
    return opts


def update_job(job_id, **fields):
    with jobs_lock:
        if job_id in jobs:
            jobs[job_id].update(fields)


def run_job(job_id, url, opts, entry):
    stream_index = {"n": 0, "last": None}

    def progress_hook(d):
        filename = d.get("filename")
        if filename != stream_index["last"]:
            stream_index["last"] = filename
            stream_index["n"] += 1
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            done = d.get("downloaded_bytes") or 0
            update_job(
                job_id,
                status="downloading",
                stream=stream_index["n"],
                percent=round(done / total * 100, 1) if total else None,
                speed=d.get("speed"),
                eta=d.get("eta"),
            )

    def postprocessor_hook(d):
        if d["status"] == "started":
            update_job(job_id, status="processing", percent=100)

    opts["progress_hooks"] = [progress_hook]
    opts["postprocessor_hooks"] = [postprocessor_hook]

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([url])
        job_dir = jobs[job_id]["dir"]
        files = [f for f in os.listdir(job_dir) if not f.endswith((".part", ".ytdl"))]
        if not files:
            raise RuntimeError("Shkarkimi përfundoi, por skedari nuk u gjet.")
        filepath = max((os.path.join(job_dir, f) for f in files), key=os.path.getsize)
        location = None
        if DESKTOP:
            filepath, location = save_to_folder(filepath, {**entry, "id": job_id})
            shutil.rmtree(job_dir, ignore_errors=True)
        update_job(job_id, status="finished", percent=100, file=filepath,
                   filename=os.path.basename(filepath), location=location)
    except Exception as exc:  # noqa: BLE001 - gabimi i dërgohet përdoruesit
        update_job(job_id, status="error", error=str(exc))


@app.post("/api/download")
def download():
    body = request.get_json(silent=True) or {}
    url = validate_url((body.get("url") or "").strip())
    mode = body.get("mode", "video")
    container = body.get("container", "mp4")
    height = body.get("height")
    bitrate = str(body.get("bitrate", "192"))

    if mode == "audio":
        if container not in AUDIO_FORMATS:
            abort(400, description="Format audio i panjohur.")
        if bitrate not in AUDIO_BITRATES:
            bitrate = "192"
    elif mode == "video":
        if container not in VIDEO_CONTAINERS:
            abort(400, description="Format video i panjohur.")
        if not str(height).isdigit():
            abort(400, description="Zgjidhni një rezolucion.")
    else:
        abort(400, description="Mënyrë e panjohur.")

    job_id = uuid.uuid4().hex
    job_dir = tempfile.mkdtemp(prefix=f"ytdl-{job_id[:8]}-")
    with jobs_lock:
        jobs[job_id] = {"status": "queued", "percent": 0, "dir": job_dir, "created": time.time()}

    opts = build_download_opts(job_dir, mode, height, container, bitrate)
    entry = {
        "title": str(body.get("title") or ""),
        "thumbnail": str(body.get("thumbnail") or ""),
        "format": container,
        "quality": str(body.get("quality") or ""),
    }
    threading.Thread(target=run_job, args=(job_id, url, opts, entry), daemon=True).start()
    return jsonify(job_id=job_id)


@app.get("/api/progress/<job_id>")
def progress(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
        if not job:
            abort(404)
        public = {k: v for k, v in job.items() if k not in ("dir", "file", "created")}
    return jsonify(public)


@app.get("/api/file/<job_id>")
def get_file(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if not job or job.get("status") != "finished":
        abort(404)
    return send_file(job["file"], as_attachment=True, download_name=job["filename"])


# ---- Endpoint-et e app-it për Windows ----

def platform_info():
    folder = download_folder()
    return {
        "folder": folder,
        "customFolder": folder != DEFAULT_FOLDER,
        "canChooseFolder": True,
        "about": f"Shkarko {APP_VERSION} · yt-dlp {yt_dlp.version.__version__}",
    }


@app.get("/api/platform")
def platform():
    desktop_only()
    return jsonify(platform_info())


@app.post("/api/folder/choose")
def folder_choose():
    desktop_only()
    folder = choose_folder_dialog(download_folder()) if choose_folder_dialog else None
    if not folder:
        return jsonify(error="cancelled"), 400
    with data_lock:
        _save("settings.json", {**_load("settings.json", {}), "folder": folder})
    return jsonify(platform_info())


@app.post("/api/folder/reset")
def folder_reset():
    desktop_only()
    with data_lock:
        settings = _load("settings.json", {})
        settings.pop("folder", None)
        _save("settings.json", settings)
    return jsonify(platform_info())


@app.get("/api/library")
def library():
    desktop_only()
    entries = []
    for entry in _load("history.json", []):
        entries.append({**entry, "missing": not os.path.exists(entry.get("path", ""))})
    return jsonify(entries)


def find_entry(entry_id):
    entry = next((e for e in _load("history.json", []) if e.get("id") == entry_id), None)
    if not entry:
        abort(404)
    return entry


@app.post("/api/library/<entry_id>/open")
def library_open(entry_id):
    desktop_only()
    path = find_entry(entry_id)["path"]
    if not os.path.exists(path):
        return jsonify(error="Skedari nuk gjendet më në kompjuter."), 404
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606 - hap skedarin me programin e Windows-it
    else:
        subprocess.Popen(["xdg-open", path])  # noqa: S603,S607
    return jsonify(ok=True)


@app.delete("/api/library/<entry_id>")
def library_delete(entry_id):
    desktop_only()
    with data_lock:
        history = _load("history.json", [])
        entry = next((e for e in history if e.get("id") == entry_id), None)
        if entry:
            try:
                os.remove(entry["path"])
            except OSError:
                pass
            history.remove(entry)
            _save("history.json", history)
    return jsonify(ok=True)


def cleanup_loop():
    while True:
        time.sleep(300)
        cutoff = time.time() - JOB_TTL_SECONDS
        with jobs_lock:
            expired = [jid for jid, j in jobs.items() if j["created"] < cutoff]
            for jid in expired:
                shutil.rmtree(jobs.pop(jid)["dir"], ignore_errors=True)


threading.Thread(target=cleanup_loop, daemon=True).start()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=False)
