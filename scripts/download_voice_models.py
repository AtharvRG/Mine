import os
import sys
import tarfile
import urllib.request
import subprocess
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
STAGING_DIR = BASE_DIR / "models_staging"

MODELS = {
    "vad": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        "filename": "silero_vad.onnx",
        "device_dest": "/sdcard/Android/data/com.anchor.mine/files/models/vad/",
        "is_archive": False,
    },
    "tts_kokoro": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
        "filename": "kokoro-en-v0_19.tar.bz2",
        "extract_folder": "kokoro-en-v0_19",
        "device_dest": "/sdcard/Android/data/com.anchor.mine/files/models/tts/",
        "is_archive": True,
    },
    "stt_zipformer": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        "filename": "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        "extract_folder": "sherpa-onnx-streaming-zipformer-en-2023-06-26",
        "device_dest": "/sdcard/Android/data/com.anchor.mine/files/models/stt/",
        "is_archive": True,
    }
}

def download_file(url: str, dest_path: Path):
    if dest_path.exists() and dest_path.stat().st_size > 0:
        print(f"[SKIP] {dest_path.name} already exists ({dest_path.stat().st_size} bytes).")
        return

    print(f"[DOWNLOAD] Fetching {url} -> {dest_path.name}...")
    headers = {"User-Agent": "Mozilla/5.0"}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp, open(dest_path, "wb") as f:
        total = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        chunk_size = 1024 * 1024  # 1 MB
        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            if total > 0:
                percent = (downloaded / total) * 100
                print(f"\r  Progress: {downloaded / 1024 / 1024:.1f} MB / {total / 1024 / 1024:.1f} MB ({percent:.1f}%)", end="")
            else:
                print(f"\r  Downloaded: {downloaded / 1024 / 1024:.1f} MB", end="")
        print("\n[DONE] Download complete.")

def extract_archive(archive_path: Path, extract_to: Path):
    print(f"[EXTRACT] Extracting {archive_path.name} to {extract_to}...")
    with tarfile.open(archive_path, "r:bz2") as tar:
        tar.extractall(path=extract_to)
    print("[DONE] Extraction complete.")

def push_to_device(local_path: Path, device_dest: str):
    print(f"[ADB PUSH] Pushing {local_path} -> {device_dest}...")
    cmd = ["adb", "push", str(local_path), device_dest]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0:
        print(f"[SUCCESS] {res.stdout.strip()}")
    else:
        print(f"[ERROR] adb push failed: {res.stderr.strip()}")

def main():
    STAGING_DIR.mkdir(parents=True, exist_ok=True)
    push_device = "--push" in sys.argv or "-p" in sys.argv

    for key, info in MODELS.items():
        dest = STAGING_DIR / info["filename"]
        download_file(info["url"], dest)

        if info["is_archive"]:
            extracted_dir = STAGING_DIR / info["extract_folder"]
            if not extracted_dir.exists():
                extract_archive(dest, STAGING_DIR)
            push_target = extracted_dir
        else:
            push_target = dest

        if push_device:
            push_to_device(push_target, info["device_dest"])

    print("\n[ALL MODELS ACQUIRED & STAGED]")

if __name__ == "__main__":
    main()
