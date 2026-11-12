"""
Build standalone portable Phoneel-v0.1-beta-Windows.exe using PyInstaller.
Bundles Python runtime, Tkinter UI, vgamepad (ViGEmClient.dll), and ADB tools
into a single zero-dependency portable executable for GitHub Releases.

Usage:
    python build_exe.py
"""

import subprocess
import sys
import os
import shutil


def build():
    pc_dir = os.path.dirname(os.path.abspath(__file__))
    root_dir = os.path.dirname(pc_dir)
    os.chdir(pc_dir)

    try:
        import PyInstaller
    except ImportError:
        print("[build] Installing PyInstaller...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "pyinstaller"])

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--onefile",
        "--windowed",
        "--name", "Phoneel-v0.1-beta-Windows",
        "--add-data", "tools;tools",
        "--collect-all", "vgamepad",
        "main.py"
    ]
    print("[build] Building single-file portable executable...")
    subprocess.check_call(cmd)

    built_exe = os.path.join(pc_dir, "dist", "Phoneel-v0.1-beta-Windows.exe")
    if os.path.isfile(built_exe):
        print(f"\n[OK] Portable executable built at: {built_exe}")
        print(f"     Size: {os.path.getsize(built_exe) / (1024 * 1024):.1f} MB")


if __name__ == "__main__":
    build()
