"""Intégration à Windows : icône dans la zone de notification et lancement avec Windows.

- `Tray` : icône près de l'horloge (pystray). Quand on réduit MicBoard, la fenêtre se cache et
  l'appli continue de tourner là : les raccourcis clavier marchent toujours. Clic sur l'icône =
  rouvrir ; son menu permet aussi d'activer le micro, de tout arrêter et de quitter.
- `autostart_*` : entrée « MicBoard » dans HKCU\\...\\CurrentVersion\\Run (pas besoin d'être
  administrateur). MicBoard est alors lancé à l'ouverture de session avec `--tray`, donc caché dans
  la zone de notification.
"""

from __future__ import annotations

import sys
import threading
from pathlib import Path
from typing import Callable

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "MicBoard"
TRAY_ARG = "--tray"


# --- lancement avec Windows --------------------------------------------------------------------
def autostart_supported() -> bool:
    """Seulement pour l'exe : en développement, il n'y a pas de programme stable à lancer."""
    return sys.platform == "win32" and bool(getattr(sys, "frozen", False))


def autostart_command() -> str:
    return f'"{sys.executable}" {TRAY_ARG}'


def _winreg():
    import winreg  # type: ignore
    return winreg


def autostart_get() -> str:
    """Commande enregistrée pour le lancement avec Windows (« » si aucune)."""
    if sys.platform != "win32":
        return ""
    winreg = _winreg()
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            return str(winreg.QueryValueEx(key, RUN_NAME)[0])
    except OSError:
        return ""


def autostart_set(enabled: bool) -> None:
    winreg = _winreg()
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, RUN_NAME, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(key, RUN_NAME)
            except FileNotFoundError:
                pass


def autostart_refresh() -> None:
    """Si l'exe a été déplacé depuis l'activation, l'entrée pointe vers l'ancien emplacement :
    on la remet à jour au lancement."""
    if autostart_supported():
        current = autostart_get()
        if current and current != autostart_command():
            try:
                autostart_set(True)
            except OSError:
                pass


# --- zone de notification ------------------------------------------------------------------------
class Tray:
    """Icône de MicBoard dans la zone de notification de Windows."""

    def __init__(self, icon_file: Path, on_open: Callable[[], None], on_toggle_live: Callable[[], None],
                 on_stop: Callable[[], None], on_quit: Callable[[], None], is_live: Callable[[], bool]):
        self.icon_file = icon_file
        self.on_open, self.on_toggle_live = on_open, on_toggle_live
        self.on_stop, self.on_quit, self.is_live = on_stop, on_quit, is_live
        self._icon = None

    @property
    def available(self) -> bool:
        return self._icon is not None

    def start(self) -> bool:
        """Crée l'icône (dans son propre fil d'exécution). Faux si c'est impossible ici."""
        try:
            import pystray
            from PIL import Image
            image = Image.open(self.icon_file)
        except Exception:
            return False
        item = pystray.MenuItem
        menu = pystray.Menu(
            item("Ouvrir MicBoard", lambda: self.on_open(), default=True),
            item("Micro en direct", lambda: self.on_toggle_live(), checked=lambda _i: self.is_live()),
            item("Tout arrêter", lambda: self.on_stop()),
            pystray.Menu.SEPARATOR,
            item("Quitter MicBoard", lambda: self.on_quit()),
        )
        self._icon = pystray.Icon("MicBoard", image, "MicBoard", menu)
        threading.Thread(target=self._icon.run, name="tray", daemon=True).start()
        return True

    def refresh(self) -> None:
        """Met à jour l'infobulle et la coche « Micro en direct »."""
        if self._icon is None:
            return
        try:
            self._icon.title = "MicBoard — " + ("micro en direct" if self.is_live() else "micro hors ligne")
            self._icon.update_menu()
        except Exception:
            pass

    def notify(self, message: str) -> None:
        if self._icon is not None:
            try:
                self._icon.notify(message, "MicBoard")
            except Exception:
                pass

    def stop(self) -> None:
        if self._icon is not None:
            try:
                self._icon.stop()
            except Exception:
                pass
            self._icon = None
