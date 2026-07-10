#!/usr/bin/env python3
"""
RaceGuard AI — web app.

Lets someone submit their own Java project (a .zip of source, or a public
git URL) and get back a full RaceGuard AI concurrency report: Stage 1
(deterministic static analysis) -> Stage 2 (LLM narration, run on this same
AMD GPU box via vLLM) -> rendered HTML report.

Runs analysis in a background thread and streams live progress to the
browser over Server-Sent Events (SSE), so the user watches static analysis
and per-risk AI reasoning happen in real time instead of staring at a
blank spinner for up to a minute.

Designed to run ON THE SAME MACHINE as vLLM (the AMD droplet) — that's why
LLM_BASE_URL defaults to localhost:8000/v1 with no tunnel needed, and it's
also why this literally IS "using AMD compute": every submitted project's
Stage 2 reasoning runs on this GPU, not a third-party API.

Run:
    export LLM_MODEL="Qwen/Qwen2.5-7B-Instruct"   # match whatever vLLM is serving
    python3 webapp/app.py
Then visit http://<droplet-ip>:5000
"""
import os
import sys
import subprocess
import queue
import uuid
import zipfile
from pathlib import Path
from threading import Thread

from flask import Flask, request, render_template_string, send_from_directory, redirect, Response

# So we can import the report generator as a module, not just run it as a CLI script.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
import generate_report  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent
JAR_PATH = PROJECT_ROOT / "target" / "RaceGuard_AI-1.0-SNAPSHOT.jar"
JOBS_DIR = Path("/tmp/raceguard_jobs")
JOBS_DIR.mkdir(exist_ok=True)

MAX_UPLOAD_MB = 50

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_MB * 1024 * 1024

# job_id -> {"logs": Queue, "status": "running"|"done"|"error", "dir": Path, "error": str|None}
jobs = {}


def log(job, message):
    print(message, flush=True)
    job["logs"].put(message)


def run_stage_streaming(job, main_class: str, args: list[str], cwd: Path, timeout: int) -> bool:
    """Runs one pipeline stage (a Java main class), streaming its output live into the
    job's log queue line-by-line as it's produced, instead of buffering until exit."""
    cmd = ["java", "-cp", str(JAR_PATH), main_class, *args]
    process = subprocess.Popen(
        cmd, cwd=cwd, env=os.environ.copy(),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    try:
        for line in process.stdout:
            log(job, line.rstrip())
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        log(job, f"Stage timed out after {timeout}s ({main_class})")
        return False
    return process.returncode == 0


def prepare_source(job, job_dir: Path, zip_path, git_url: str):
    """Extracts an already-saved zip or clones a git URL into job_dir/source."""
    source_dir = job_dir / "source"

    if git_url:
        log(job, f"Cloning {git_url} ...")
        result = subprocess.run(
            ["git", "clone", "--depth", "1", git_url, str(source_dir)],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            return False, f"git clone failed: {result.stderr[:500]}"
        return True, "cloned"

    if zip_path and zip_path.exists():
        log(job, "Extracting uploaded project...")
        try:
            with zipfile.ZipFile(zip_path) as zf:
                zf.extractall(source_dir)
        except zipfile.BadZipFile:
            return False, "Uploaded file isn't a valid .zip"
        return True, "extracted"

    return False, "No project provided — upload a .zip or paste a git URL"


def analyze_job(job_id: str, job_dir: Path, git_url: str, zip_path):
    """The actual pipeline, run in a background thread so /analyze can return instantly."""
    job = jobs[job_id]
    try:
        log(job, "Preparing project...")
        ok, message = prepare_source(job, job_dir, zip_path, git_url)
        if not ok:
            job["status"] = "error"
            job["error"] = message
            log(job, f"ERROR: {message}")
            return

        java_files = list((job_dir / "source").rglob("*.java"))
        log(job, f"Found {len(java_files)} Java files")
        if not java_files:
            job["status"] = "error"
            job["error"] = "No .java files found in the submitted project."
            log(job, "ERROR: no .java files found")
            return

        log(job, "Running static analysis (Stage 1)...")
        ok = run_stage_streaming(
            job, "com.raceguard.ProjectAnalyzer",
            [str(job_dir / "source"), str(job_dir / "output")],
            job_dir, timeout=120,
        )
        if not ok:
            job["status"] = "error"
            job["error"] = "Stage 1 (static analysis) failed — see log above."
            return
        log(job, "Stage 1 completed")

        log(job, "Running AI reasoning (Stage 2, on AMD GPU)...")
        ok = run_stage_streaming(
            job, "com.raceguard.Stage2Runner",
            [str(job_dir / "output")],
            job_dir, timeout=600,
        )
        if not ok:
            job["status"] = "error"
            job["error"] = "Stage 2 (LLM reasoning) failed — is vLLM running on this box? See log above."
            return
        log(job, "Stage 2 completed")

        log(job, "Generating report...")
        generate_report.generate(job_dir / "output")
        log(job, "Analysis complete")
        job["status"] = "done"

    except Exception as e:
        job["status"] = "error"
        job["error"] = str(e)
        log(job, f"ERROR: {e}")


@app.route("/", methods=["GET"])
def index():
    return render_template_string(UPLOAD_PAGE)


@app.route("/analyze", methods=["POST"])
def analyze():
    if not JAR_PATH.exists():
        return render_template_string(
            ERROR_PAGE,
            message=f"Server isn't set up yet: {JAR_PATH} doesn't exist. "
                    f"Run 'mvn package' in the project root first.",
        ), 500

    job_id = uuid.uuid4().hex[:12]
    job_dir = JOBS_DIR / job_id
    (job_dir / "source").mkdir(parents=True)
    (job_dir / "output").mkdir()

    jobs[job_id] = {"logs": queue.Queue(), "status": "running", "dir": job_dir, "error": None}

    git_url = request.form.get("git_url", "").strip()
    upload_file = request.files.get("project_zip")

    # Save the upload to disk NOW, while still inside the request — Flask's FileStorage
    # can't be safely read from a background thread after this handler returns.
    zip_path = None
    if upload_file and upload_file.filename:
        zip_path = job_dir / "upload.zip"
        upload_file.save(zip_path)

    if not git_url and not zip_path:
        return render_template_string(
            ERROR_PAGE, message="No project provided — upload a .zip or paste a git URL"
        ), 400

    Thread(target=analyze_job, args=(job_id, job_dir, git_url, zip_path), daemon=True).start()

    return redirect(f"/status/{job_id}")


@app.route("/status/<job_id>")
def status(job_id):
    if job_id not in jobs:
        return render_template_string(ERROR_PAGE, message="Unknown job ID."), 404
    return render_template_string(PROGRESS_PAGE, job_id=job_id)


@app.route("/stream/<job_id>")
def stream(job_id):
    if job_id not in jobs:
        return "Unknown job", 404
    job = jobs[job_id]

    def events():
        while True:
            try:
                message = job["logs"].get(timeout=1)
                yield f"data: {message}\n\n"
            except queue.Empty:
                pass
            # Only declare completion once the queue is fully drained, so no
            # log lines get dropped in the race between finishing and polling.
            if job["status"] in ("done", "error") and job["logs"].empty():
                if job["status"] == "done":
                    yield "event: done\ndata: done\n\n"
                else:
                    yield f"event: failed\ndata: {job.get('error') or 'unknown error'}\n\n"
                break

    return Response(events(), mimetype="text/event-stream")


@app.route("/report/<job_id>")
def report(job_id):
    if job_id not in jobs:
        return render_template_string(ERROR_PAGE, message="Unknown job ID."), 404
    return send_from_directory(jobs[job_id]["dir"] / "output", "report.html")


UPLOAD_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>RaceGuard AI</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<style>
  :root {
    --bg: #10141A; --surface: #171D26; --border: #2A323E;
    --text: #E7ECF2; --text-muted: #8B96A5; --t2: #3DC4B0; --fix: #4CAF7D;
    --font-display: 'IBM Plex Sans', system-ui, sans-serif;
    --font-mono: 'IBM Plex Mono', 'SF Mono', Consolas, monospace;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font-family: var(--font-display); min-height: 100vh;
    display: flex; align-items: center; justify-content: center;
  }
  .wrap { max-width: 560px; width: 100%; padding: 24px; }
  .eyebrow {
    font-family: var(--font-mono); font-size: 13px; letter-spacing: 0.08em;
    color: var(--t2); text-transform: uppercase; margin-bottom: 12px;
  }
  h1 { font-size: 40px; font-weight: 700; margin: 0 0 12px; letter-spacing: -0.02em; }
  p.tagline { color: var(--text-muted); font-size: 16px; margin: 0 0 32px; }
  form {
    background: var(--surface); border: 1px solid var(--border);
    border-radius: 12px; padding: 28px;
  }
  label {
    display: block; font-family: var(--font-mono); font-size: 12px;
    text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted);
    margin-bottom: 8px;
  }
  input[type="text"], input[type="file"] {
    width: 100%; background: #0B0E13; border: 1px solid var(--border);
    color: var(--text); padding: 10px 12px; border-radius: 8px;
    font-family: var(--font-mono); font-size: 14px; margin-bottom: 20px;
  }
  .or-divider {
    text-align: center; color: var(--text-muted); font-size: 12px;
    font-family: var(--font-mono); margin: -8px 0 20px; text-transform: uppercase;
  }
  button {
    width: 100%; background: var(--fix); color: #0A1210; border: none;
    padding: 14px; border-radius: 8px; font-family: var(--font-display);
    font-size: 15px; font-weight: 600; cursor: pointer;
  }
  button:hover { opacity: 0.9; }
  .note {
    font-family: var(--font-mono); font-size: 12px; color: var(--text-muted);
    margin-top: 16px; text-align: center;
  }
</style>
</head>
<body>
<div class="wrap">
  <div class="eyebrow">Concurrency Validation Platform</div>
  <h1>RaceGuard AI</h1>
  <p class="tagline">
    Submit a Java project. RaceGuard AI statically analyzes it for concurrency
    defects, then explains and prioritizes fixes using an LLM running on this
    AMD GPU.
  </p>
  <form action="/analyze" method="post" enctype="multipart/form-data">
    <label for="project_zip">Upload a .zip of your Java source</label>
    <input type="file" id="project_zip" name="project_zip" accept=".zip">
    <div class="or-divider">or</div>
    <label for="git_url">Paste a public git URL</label>
    <input type="text" id="git_url" name="git_url" placeholder="https://github.com/you/your-repo">
    <button type="submit">Analyze for concurrency risks</button>
  </form>
  <div class="note">Analysis usually takes 20–60 seconds.</div>
</div>
</body>
</html>
"""

PROGRESS_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>RaceGuard AI — Analyzing...</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<style>
  :root {
    --bg: #10141A; --surface: #171D26; --border: #2A323E;
    --text: #E7ECF2; --text-muted: #8B96A5; --t2: #3DC4B0; --high: #E2574C;
    --font-display: 'IBM Plex Sans', system-ui, sans-serif;
    --font-mono: 'IBM Plex Mono', 'SF Mono', Consolas, monospace;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font-family: var(--font-display); min-height: 100vh;
    display: flex; align-items: center; justify-content: center;
  }
  .wrap { max-width: 640px; width: 100%; padding: 24px; }
  .eyebrow {
    font-family: var(--font-mono); font-size: 13px; letter-spacing: 0.08em;
    color: var(--t2); text-transform: uppercase; margin-bottom: 12px;
  }
  h1 { font-size: 26px; font-weight: 700; margin: 0 0 20px; display: flex; align-items: center; gap: 10px; }
  .spinner {
    width: 16px; height: 16px; border-radius: 50%; flex-shrink: 0;
    border: 2px solid var(--border); border-top-color: var(--t2);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  pre#logs {
    background: #0B0E13; border: 1px solid var(--border); border-radius: 10px;
    padding: 16px; font-family: var(--font-mono); font-size: 13px; color: var(--text);
    max-height: 420px; overflow-y: auto; white-space: pre-wrap; margin: 0;
  }
  .error-banner {
    display: none; background: rgba(226,87,76,0.1); border: 1px solid var(--high);
    color: var(--high); padding: 12px 14px; border-radius: 8px; margin-bottom: 16px;
    font-family: var(--font-mono); font-size: 13px; white-space: pre-wrap;
  }
</style>
</head>
<body>
<div class="wrap">
  <div class="eyebrow">RaceGuard AI</div>
  <h1><span class="spinner" id="spinner"></span><span id="heading">Analyzing your project...</span></h1>
  <div class="error-banner" id="error-banner"></div>
  <pre id="logs"></pre>
</div>
<script>
  const logs = document.getElementById("logs");
  const heading = document.getElementById("heading");
  const spinner = document.getElementById("spinner");
  const errorBanner = document.getElementById("error-banner");
  const source = new EventSource("/stream/{{ job_id }}");

  source.onmessage = function(e) {
    logs.textContent += e.data + "\\n";
    logs.scrollTop = logs.scrollHeight;
  };

  source.addEventListener("done", function() {
    heading.textContent = "Complete! Redirecting...";
    spinner.style.display = "none";
    source.close();
    window.location = "/report/{{ job_id }}";
  });

  source.addEventListener("failed", function(e) {
    heading.textContent = "Analysis failed";
    spinner.style.display = "none";
    errorBanner.style.display = "block";
    errorBanner.textContent = e.data;
    source.close();
  });
</script>
</body>
</html>
"""

ERROR_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>RaceGuard AI — Error</title>
<style>
  body { background: #10141A; color: #E7ECF2; font-family: system-ui, sans-serif;
         display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
  .box { max-width: 640px; padding: 32px; background: #171D26; border: 1px solid #2A323E;
         border-left: 3px solid #E2574C; border-radius: 10px; }
  h1 { margin-top: 0; font-size: 20px; color: #E2574C; }
  pre { white-space: pre-wrap; font-size: 13px; color: #8B96A5; background: #0B0E13;
        padding: 12px; border-radius: 8px; max-height: 300px; overflow: auto; }
  a { color: #3DC4B0; }
</style>
</head>
<body>
<div class="box">
  <h1>Analysis failed</h1>
  <pre>{{ message }}</pre>
  <p><a href="/">&larr; Try again</a></p>
</div>
</body>
</html>
"""

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, threaded=True)