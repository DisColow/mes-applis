"""Raccourcis clavier globaux par l'API officielle de Windows (RegisterHotKey).

C'est Windows lui-même qui surveille les combinaisons et envoie un message WM_HOTKEY à MicBoard,
quelle que soit la fenêtre au premier plan (jeu, Discord…) et même quand MicBoard est caché dans la
zone de notification. La touche est traduite en code de touche Windows (VK) d'après la disposition
du clavier active : la touche A d'un AZERTY donne bien « A ».

RegisterHotKey doit être appelé depuis le fil d'exécution qui lit ensuite les messages : un fil
dédié tient donc une boucle de messages et fait les (dés)enregistrements à la demande.
"""

from __future__ import annotations

import ctypes
import queue
import threading
from typing import Callable

MOD_ALT, MOD_CONTROL, MOD_SHIFT, MOD_WIN, MOD_NOREPEAT = 0x1, 0x2, 0x4, 0x8, 0x4000
MODIFIERS = {"alt": MOD_ALT, "ctrl": MOD_CONTROL, "shift": MOD_SHIFT, "windows": MOD_WIN}
WM_HOTKEY, WM_APP = 0x0312, 0x8000
ERROR_HOTKEY_ALREADY_REGISTERED = 1409

NAMED_VK = {
    "space": 0x20, "enter": 0x0D, "tab": 0x09, "esc": 0x1B, "backspace": 0x08, "pause": 0x13,
    "page up": 0x21, "page down": 0x22, "end": 0x23, "home": 0x24, "left": 0x25, "up": 0x26,
    "right": 0x27, "down": 0x28, "print screen": 0x2C, "insert": 0x2D, "delete": 0x2E,
    "menu": 0x5D, "caps lock": 0x14, "scroll lock": 0x91, "num lock": 0x90,
    **{f"f{i}": 0x6F + i for i in range(1, 25)},
}
# Pavé numérique (scan codes de micboard.hotkeys.NUMPAD) : VK propres au pavé, Verr. num. activé.
NUMPAD_VK = {82: 0x60, 79: 0x61, 80: 0x62, 81: 0x63, 75: 0x64, 76: 0x65, 77: 0x66, 71: 0x67, 72: 0x68,
             73: 0x69, 55: 0x6A, 78: 0x6B, 74: 0x6D, 83: 0x6E}


class HotkeyError(Exception):
    pass


def combo_to_vk(combo: str, scan_to_vk: Callable[[int], int], char_to_vk: Callable[[str], int]) -> tuple[int, int]:
    """« ctrl+alt+#16 » -> (modificateurs, code de touche VK). Lève HotkeyError si impossible."""
    mods, vk = 0, 0
    for token in [t for t in combo.split("+") if t]:
        if token in MODIFIERS:
            mods |= MODIFIERS[token]
        elif token.startswith("#") and token[1:].isdigit():
            sc = int(token[1:])
            vk = NUMPAD_VK.get(sc) or scan_to_vk(sc)
        elif token in NAMED_VK:
            vk = NAMED_VK[token]
        elif len(token) == 1:
            vk = char_to_vk(token)
        else:
            raise HotkeyError(f"touche inconnue « {token} »")
    if not vk:
        raise HotkeyError("il faut une touche en plus de Ctrl, Alt, Maj ou Win")
    return mods, vk


class _MSG(ctypes.Structure):
    _fields_ = [("hwnd", ctypes.c_void_p), ("message", ctypes.c_uint), ("wParam", ctypes.c_size_t),
                ("lParam", ctypes.c_ssize_t), ("time", ctypes.c_uint), ("pt_x", ctypes.c_long),
                ("pt_y", ctypes.c_long)]


class WinHotkeys:
    """Gestionnaire de raccourcis globaux Windows. Toutes les méthodes publiques sont sûres depuis
    n'importe quel fil d'exécution."""

    def __init__(self):
        self._user32 = ctypes.WinDLL("user32", use_last_error=True)
        u = self._user32
        u.RegisterHotKey.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_uint, ctypes.c_uint]
        u.UnregisterHotKey.argtypes = [ctypes.c_void_p, ctypes.c_int]
        u.GetMessageW.argtypes = [ctypes.POINTER(_MSG), ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint]
        u.PeekMessageW.argtypes = [ctypes.POINTER(_MSG), ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint, ctypes.c_uint]
        u.PostThreadMessageW.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
        u.MapVirtualKeyExW.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p]
        u.GetKeyboardLayout.restype = ctypes.c_void_p
        u.VkKeyScanW.argtypes = [ctypes.c_wchar]
        u.VkKeyScanW.restype = ctypes.c_short
        self._callbacks: dict[int, Callable[[], None]] = {}
        self._requests: queue.Queue = queue.Queue()
        self._ready = threading.Event()
        self._thread_id = 0
        threading.Thread(target=self._loop, name="raccourcis", daemon=True).start()
        self._ready.wait(5)

    # --- conversions de touches (disposition du clavier active) ---------------------------------
    def _scan_to_vk(self, sc: int) -> int:
        layout = self._user32.GetKeyboardLayout(0)
        return self._user32.MapVirtualKeyExW(sc, 3, layout)  # MAPVK_VSC_TO_VK_EX

    def _char_to_vk(self, ch: str) -> int:
        res = self._user32.VkKeyScanW(ch)
        return 0 if res == -1 else res & 0xFF

    # --- boucle de messages ---------------------------------------------------------------------
    def _loop(self) -> None:
        msg = _MSG()
        self._thread_id = ctypes.windll.kernel32.GetCurrentThreadId()
        self._user32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 0)  # crée la file de messages
        self._ready.set()
        while self._user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            if msg.message == WM_HOTKEY:
                cb = self._callbacks.get(int(msg.wParam))
                if cb is not None:  # le son se lance hors de la boucle, pour ne rien bloquer
                    threading.Thread(target=cb, daemon=True).start()
            elif msg.message == WM_APP:
                while not self._requests.empty():
                    fn, box, done = self._requests.get()
                    try:
                        box.append(fn())
                    except Exception as e:  # renvoyée à l'appelant
                        box.append(e)
                    done.set()

    def _call(self, fn):
        """Exécute fn dans le fil des raccourcis (obligatoire pour RegisterHotKey)."""
        box, done = [], threading.Event()
        self._requests.put((fn, box, done))
        self._user32.PostThreadMessageW(self._thread_id, WM_APP, 0, 0)
        if not done.wait(5):
            raise HotkeyError("le gestionnaire de raccourcis ne répond pas")
        if isinstance(box[0], Exception):
            raise box[0]
        return box[0]

    # --- API --------------------------------------------------------------------------------------
    def clear(self) -> None:
        def run():
            for hid in list(self._callbacks):
                self._user32.UnregisterHotKey(None, hid)
            self._callbacks.clear()
        self._call(run)

    def add(self, combo: str, callback: Callable[[], None]) -> None:
        """Enregistre « ctrl+alt+#16 ». Lève HotkeyError avec une explication en cas d'échec."""
        def run():
            mods, vk = combo_to_vk(combo, self._scan_to_vk, self._char_to_vk)
            hid = len(self._callbacks) + 1
            while hid in self._callbacks:
                hid += 1
            if not self._user32.RegisterHotKey(None, hid, mods | MOD_NOREPEAT, vk):
                err = ctypes.get_last_error()
                if err == ERROR_HOTKEY_ALREADY_REGISTERED:
                    raise HotkeyError("déjà utilisé par une autre application")
                raise HotkeyError(f"refusé par Windows (erreur {err})")
            self._callbacks[hid] = callback
        self._call(run)
