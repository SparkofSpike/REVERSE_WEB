#!/usr/bin/env python3
"""
Reserve_Web (TEST) - local one-click deploy script

Usage:
    python ship.py                # Full deploy (build frontend+backend -> upload -> verify -> restart)
    python ship.py --upload-only  # Upload already-built jar only (skip builds)
    python ship.py --help         # Show help

Requirements:
    - Python 3.8+
    - Node.js 20+  (frontend build)
    - JDK 21       (backend build)
    - Maven        (backend build)
    - OpenSSH      (scp/ssh, key C:\\Users\\asus\\.ssh\\test_deploy)

Notes:
    Local machine to Tencent Cloud is domestic direct link, a single 50MB jar
    scp is reliable (chunking is only needed cross-border). After upload the
    script verifies SHA256 + jar integrity before starting the service.
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

# ======================== Config ========================
SERVER_HOST = "111.229.241.95"
SERVER_PORT = 22
SERVER_USER = "Administrator"
SSH_KEY = str(Path.home() / ".ssh" / "test_deploy")
SERVER_PATH = "C:/Reverse_Web"
JAR_NAME = "test-engine-0.1.0-SNAPSHOT.jar"
TASK_NAME = "TestEngine"
JAR_EXE = r"C:\tools\jdk-21.0.12+8\bin\jar.exe"
SITE_URL = "http://111.229.241.95/"

PROJECT_ROOT = Path(__file__).resolve().parent
FRONTEND_DIR = PROJECT_ROOT / "frontend"
BACKEND_DIR = PROJECT_ROOT / "backend"
STATIC_DIR = BACKEND_DIR / "src" / "main" / "resources" / "static"


# ======================== Helpers ========================
def log(msg: str, ok: bool = True):
    """Print log line with status marker"""
    icon = "OK  " if ok else "FAIL"
    print(f"  [{icon}] {msg}")


def _subprocess_run(cmd: list, cwd=None, capture=False):
    """Run subprocess, tolerate Windows GBK encoding"""
    kwargs = dict(cwd=cwd, capture_output=capture, check=False)
    try:
        return subprocess.run(cmd, text=True, errors="replace", **kwargs)
    except TypeError:
        return subprocess.run(cmd, text=True, **kwargs)


def run(cmd: list, cwd: Path | None = None, capture: bool = False) -> subprocess.CompletedProcess:
    """Run command and print it"""
    if os.name == "nt" and not cmd[0].lower().endswith((".exe", ".cmd")):
        # Windows: npm/mvn ship as .cmd shims without .exe; bare names
        # cannot be launched by subprocess (WinError 2)
        cmd = [cmd[0] + ".cmd", *cmd[1:]]
    print(f"  $ {' '.join(str(c) for c in cmd)}")
    try:
        return _subprocess_run(cmd, cwd=cwd, capture=capture)
    except FileNotFoundError:
        print(f"    [ERROR] command not found: {cmd[0]}")
        sys.exit(1)


def ssh(cmd: str) -> subprocess.CompletedProcess:
    """Run command on the server over SSH (server default shell is PowerShell)"""
    return _subprocess_run([
        "ssh", "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=NUL",
        "-o", "ConnectTimeout=20",
        "-p", str(SERVER_PORT),
        "-i", SSH_KEY,
        f"{SERVER_USER}@{SERVER_HOST}",
        cmd,
    ], capture=True)


def scp_upload(local: Path, remote_path: str) -> subprocess.CompletedProcess:
    """Upload one file over SCP"""
    return _subprocess_run([
        "scp", "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=NUL",
        "-o", "ConnectTimeout=30",
        "-o", "ServerAliveInterval=15",
        "-P", str(SERVER_PORT),
        "-i", SSH_KEY,
        str(local),
        f"{SERVER_USER}@{SERVER_HOST}:{remote_path}",
    ], capture=True)


def sha256_of(path: Path) -> str:
    """Compute local file SHA256 (uppercase)"""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


# ======================== Build steps ========================
def step_build_frontend() -> bool:
    """Build frontend"""
    print("\n[1/5] Building frontend...")
    if not (FRONTEND_DIR / "node_modules").exists():
        log("installing deps (npm ci)...")
        r = run(["npm", "ci"], cwd=FRONTEND_DIR, capture=True)
        if r.returncode != 0:
            log(f"npm ci failed: {r.stderr.strip()[-200:]}", ok=False)
            return False
    r = run(["npm", "run", "build"], cwd=FRONTEND_DIR, capture=True)
    if r.returncode != 0:
        log(f"frontend build failed: {r.stderr.strip()[-300:]}", ok=False)
        return False
    log("frontend build done")
    return True


def step_copy_static() -> bool:
    """Copy frontend dist into backend static"""
    print("\n[2/5] Copying frontend to backend static...")
    if STATIC_DIR.exists():
        shutil.rmtree(STATIC_DIR)
    STATIC_DIR.mkdir(parents=True)

    dist_dir = FRONTEND_DIR / "dist"
    if not dist_dir.exists():
        log("dist dir missing, skipped", ok=False)
        return False

    for item in dist_dir.iterdir():
        dest = STATIC_DIR / item.name
        if item.is_dir():
            shutil.copytree(item, dest)
        else:
            shutil.copy2(item, dest)
    log(f"static files copied ({dist_dir} -> {STATIC_DIR})")
    return True


def step_build_backend() -> bool:
    """Build backend jar"""
    print("\n[3/5] Building backend...")
    r = run(["mvn", "package", "-DskipTests"], cwd=BACKEND_DIR, capture=True)
    if r.returncode != 0:
        log(f"backend build failed: {r.stderr.strip()[-300:]}", ok=False)
        return False
    log("backend build done")
    return True


# ======================== Deploy steps ========================
def step_stop_service() -> bool:
    """Stop service on server (release jar file lock)"""
    print("\n[4/5] Stopping service on server...")
    ps = (
        f"powershell -NoProfile -Command "
        f"\"Stop-ScheduledTask -TaskName {TASK_NAME} -ErrorAction SilentlyContinue; "
        f"Stop-Process -Name java -Force -ErrorAction SilentlyContinue; "
        f"Start-Sleep -Seconds 2; Write-Host STOPPED\""
    )
    r = ssh(ps)
    if r.returncode != 0 and "STOPPED" not in r.stdout:
        log(f"stop command issue: {r.stderr.strip()}", ok=False)
        return False
    log("service stopped")
    return True


def step_upload_and_verify(local_jar: Path) -> bool:
    """Upload jar and verify SHA256 + jar integrity"""
    print(f"\n[5/5] Uploading {JAR_NAME} ({local_jar.stat().st_size / 1024 / 1024:.1f} MB)...")

    local_sha = sha256_of(local_jar)

    r = scp_upload(local_jar, f"{SERVER_PATH}/{JAR_NAME}")
    if r.returncode != 0:
        log(f"scp upload failed: {r.stderr.strip()[-300:]}", ok=False)
        return False
    log("scp upload done")

    # Compare SHA256 on server
    ps = (
        f"powershell -NoProfile -Command "
        f"\"(Get-FileHash '{SERVER_PATH}\\{JAR_NAME}' -Algorithm SHA256).Hash\""
    )
    r = ssh(ps)
    remote_sha = r.stdout.strip()
    if remote_sha != local_sha:
        log(f"SHA256 mismatch! local={local_sha} server={remote_sha}", ok=False)
        return False
    log(f"SHA256 verified: {local_sha[:16]}...")

    # Jar integrity check (TestApplication.class present)
    ps = (
        f"powershell -NoProfile -Command "
        f"\"& '{JAR_EXE}' tf '{SERVER_PATH}\\{JAR_NAME}' | Select-String 'TestApplication.class' | Out-Null; "
        f"if($LASTEXITCODE -ne 0){{ throw 'jar tf check failed' }}; Write-Host JAR_OK\""
    )
    r = ssh(ps)
    if "JAR_OK" not in r.stdout:
        log(f"jar integrity check failed: {r.stderr.strip()}", ok=False)
        return False
    log("jar integrity verified (TestApplication.class)")
    return True


def step_start_and_verify() -> bool:
    """Start service and verify HTTP 200"""
    print("\nStarting service and verifying...")
    ps = f"powershell -NoProfile -Command \"Start-ScheduledTask -TaskName {TASK_NAME}; Write-Host STARTED\""
    r = ssh(ps)
    if "STARTED" not in r.stdout and r.returncode != 0:
        log(f"start command issue: {r.stderr.strip()}", ok=False)
        return False

    # Wait for readiness (Spring Boot cold start ~10-20s)
    for i in range(20):
        time.sleep(3)
        try:
            with urllib.request.urlopen(SITE_URL, timeout=10) as resp:
                if resp.status == 200:
                    log(f"service up: {SITE_URL} (HTTP 200)")
                    return True
        except Exception:
            pass
        if i % 4 == 3:
            print(f"    ...waiting for service ({i + 1}/20)")

    log("service did not return 200 within 60s, check server manually", ok=False)
    return False


# ======================== Main ========================
def main():
    parser = argparse.ArgumentParser(description="Reserve_Web (TEST) local one-click deploy")
    parser.add_argument("--upload-only", action="store_true", help="upload already-built jar only (skip builds)")
    args = parser.parse_args()

    print("=" * 52)
    print("  Reserve_Web (TEST) - local deploy")
    print(f"  -> {SERVER_USER}@{SERVER_HOST}:{SERVER_PATH}")
    print("=" * 52)

    if not Path(SSH_KEY).exists():
        log(f"SSH key missing: {SSH_KEY}", ok=False)
        sys.exit(1)

    local_jar = BACKEND_DIR / "target" / JAR_NAME

    if args.upload_only:
        if not local_jar.exists():
            log(f"jar missing: {local_jar}, run full deploy first", ok=False)
            sys.exit(1)
        ok = step_stop_service() and step_upload_and_verify(local_jar) and step_start_and_verify()
    else:
        ok = (
            step_build_frontend()
            and step_copy_static()
            and step_build_backend()
            and step_stop_service()
            and step_upload_and_verify(local_jar)
            and step_start_and_verify()
        )

    print("\n" + "=" * 52)
    if ok:
        print("  Deploy succeeded!")
    else:
        print("  Deploy failed, check logs above")
        sys.exit(1)
    print("=" * 52)


if __name__ == "__main__":
    main()
