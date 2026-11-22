"""Fenêtre de MicBoard : l'interface HTML (dossier ui/) dans une fenêtre Windows native.

pywebview utilise le moteur d'Edge (WebView2), déjà présent sur Windows 10 et 11.
"""

from __future__ import annotations

import sys
from pathlib import Path

from . import system
from .api import APP, Api


def ui_dir() -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))  # exe PyInstaller
    return base / "micboard" / "ui"


def main() -> None:
    import webview

    api = Api()
    system.autostart_refresh()

    # Icône dans la zone de notification ; « --tray » (lancement avec Windows) démarre caché dedans.
    tray = system.Tray(assets_dir() / "micboard.png", on_open=api._show_window,
                       on_toggle_live=api._tray_toggle_live, on_stop=lambda: api.stop(),
                       on_quit=api.win_close, is_live=lambda: api._engine.running)
    tray_ok = tray.start()
    api._tray = tray if tray_ok else None
    hidden = system.TRAY_ARG in sys.argv and tray_ok

    # Sans cadre Windows : la barre de titre (déplacer, réduire, agrandir, fermer) est dans ui/.
    webview.settings["DRAG_REGION_SELECTOR"] = ".pywebview-drag-region"
    window = webview.create_window(
        APP, str(ui_dir() / "index.html"), js_api=api, frameless=True, easy_drag=False, hidden=hidden,
        width=1280, height=820, min_size=(900, 600), background_color="#0b0d12")
    api._attach(window)
    window.events.shown += api._frame.on_shown
    window.events.loaded += api._on_window_ready
    window.events.closed += api.shutdown
    icon = icon_path()
    webview.start(debug="--debug" in sys.argv, icon=str(icon) if icon.exists() else None)


def assets_dir() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent)) / "assets"


def icon_path() -> Path:
    """Icône de la fenêtre hors exe (dans l'exe, Windows la reprend de celle de l'exe)."""
    return assets_dir() / "micboard.ico"
