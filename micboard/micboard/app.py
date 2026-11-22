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
    # Sans cadre Windows : la barre de titre (déplacer, réduire, agrandir, fermer) est dans ui/.
    webview.settings["DRAG_REGION_SELECTOR"] = ".pywebview-drag-region"
    window = webview.create_window(
        APP, str(ui_dir() / "index.html"), js_api=api, frameless=True, easy_drag=False,
        width=1280, height=820, min_size=(900, 600), background_color="#0b0d12")
    api._attach(window)
    window.events.shown += api._frame.on_shown
    window.events.closed += api.shutdown
    icon = icon_path()
    webview.start(debug="--debug" in sys.argv, icon=str(icon) if icon.exists() else None)


def icon_path() -> Path:
    """Icône de la fenêtre hors exe (dans l'exe, Windows la reprend de celle de l'exe)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / "assets" / "micboard.ico"
