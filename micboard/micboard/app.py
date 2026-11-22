"""Fenêtre de MicBoard : l'interface HTML (dossier ui/) dans une fenêtre Windows native.

pywebview utilise le moteur d'Edge (WebView2), déjà présent sur Windows 10 et 11.
"""

from __future__ import annotations

import sys
from pathlib import Path

from .api import APP, Api


def ui_dir() -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))  # exe PyInstaller
    return base / "micboard" / "ui"


def main() -> None:
    import webview

    api = Api()
    window = webview.create_window(
        APP, str(ui_dir() / "index.html"), js_api=api,
        width=1280, height=820, min_size=(900, 600), background_color="#0d0f14")
    api._attach(window)
    window.events.closed += api.shutdown
    webview.start(debug="--debug" in sys.argv)
