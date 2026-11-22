"""Fenêtre sans bordure Windows : agrandir/restaurer et redimensionner nous-mêmes.

La barre de titre est dessinée dans l'interface (ui/), donc la fenêtre n'a plus de cadre Windows.
Deux conséquences à compenser :
- « agrandir » une fenêtre sans cadre recouvre la barre des tâches : on la place nous-mêmes sur la
  zone de travail de l'écran (MonitorFromWindow / GetMonitorInfo) ;
- plus de bords à tirer : l'interface envoie la nouvelle géométrie, appliquée en un seul SetWindowPos.

Les coordonnées échangées avec l'interface sont en pixels logiques, comme partout dans pywebview.
Hors de Windows (développement), on se rabat sur les fonctions de pywebview.
"""

from __future__ import annotations

import sys

SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SWP_SHOWWINDOW = 0x0040
MONITOR_DEFAULTTONEAREST = 2
DWMWA_WINDOW_CORNER_PREFERENCE = 33
DWMWCP_ROUND = 2


def _win32():
    import ctypes
    from ctypes import wintypes

    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                    ("right", ctypes.c_long), ("bottom", ctypes.c_long)]

    class MONITORINFO(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", RECT), ("rcWork", RECT),
                    ("dwFlags", wintypes.DWORD)]

    u = ctypes.windll.user32
    u.GetWindowRect.argtypes = [ctypes.c_void_p, ctypes.POINTER(RECT)]
    u.MonitorFromWindow.argtypes = [ctypes.c_void_p, wintypes.DWORD]
    u.MonitorFromWindow.restype = ctypes.c_void_p
    u.GetMonitorInfoW.argtypes = [ctypes.c_void_p, ctypes.POINTER(MONITORINFO)]
    u.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int,
                               ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    return ctypes, u, RECT, MONITORINFO


class Frame:
    MIN_W, MIN_H = 900, 600

    def __init__(self):
        self.window = None
        self.maximized = False
        self._saved = None  # géométrie physique avant agrandissement

    def attach(self, window) -> None:
        self.window = window

    # --- outils ---------------------------------------------------------------------------
    def _native(self):
        if sys.platform != "win32" or self.window is None:
            return None
        return getattr(self.window, "native", None)

    def _hwnd(self, native):
        return native.Handle.ToInt64()

    def _scale(self, native) -> float:
        return float(getattr(native, "_scale", 1.0) or 1.0)

    def on_shown(self) -> None:
        """Coins arrondis de Windows 11 (sans effet ailleurs)."""
        native = self._native()
        if native is None:
            return
        try:
            import ctypes
            pref = ctypes.c_int(DWMWCP_ROUND)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                ctypes.c_void_p(self._hwnd(native)), DWMWA_WINDOW_CORNER_PREFERENCE,
                ctypes.byref(pref), ctypes.sizeof(pref))
        except Exception:
            pass

    # --- actions de la barre de titre ------------------------------------------------------------
    def minimize(self) -> None:
        self.window.minimize()

    def close(self) -> None:
        self.window.destroy()

    def toggle_maximize(self) -> bool:
        native = self._native()
        if native is None:  # développement hors Windows
            (self.window.restore if self.maximized else self.window.maximize)()
            self.maximized = not self.maximized
            return self.maximized
        ctypes, u, RECT, MONITORINFO = _win32()
        hwnd = self._hwnd(native)
        if self.maximized and self._saved:
            x, y, w, h = self._saved
            self.maximized = False
        else:
            r = RECT()
            u.GetWindowRect(hwnd, ctypes.byref(r))
            self._saved = (r.left, r.top, r.right - r.left, r.bottom - r.top)
            mi = MONITORINFO()
            mi.cbSize = ctypes.sizeof(MONITORINFO)
            u.GetMonitorInfoW(u.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), ctypes.byref(mi))
            w_ = mi.rcWork
            x, y, w, h = w_.left, w_.top, w_.right - w_.left, w_.bottom - w_.top
            self.maximized = True
        u.SetWindowPos(hwnd, None, x, y, w, h, SWP_NOZORDER | SWP_SHOWWINDOW)
        return self.maximized

    def geometry(self) -> dict:
        """Position et taille en pixels logiques."""
        native = self._native()
        if native is None:
            w = self.window
            return {"x": w.x, "y": w.y, "w": w.width, "h": w.height, "maximized": self.maximized}
        s = self._scale(native)
        return {"x": native.Left / s, "y": native.Top / s, "w": native.Width / s,
                "h": native.Height / s, "maximized": self.maximized}

    def set_geometry(self, x: float, y: float, w: float, h: float) -> None:
        """Applique une géométrie (pixels logiques) en un seul appel, pendant un redimensionnement."""
        w, h = max(self.MIN_W, w), max(self.MIN_H, h)
        native = self._native()
        if native is None:
            self.window.move(int(x), int(y))
            self.window.resize(int(w), int(h))
            return
        _ctypes, u, _r, _m = _win32()
        s = self._scale(native)
        u.SetWindowPos(self._hwnd(native), None, round(x * s), round(y * s), round(w * s), round(h * s),
                       SWP_NOZORDER | SWP_NOACTIVATE)
