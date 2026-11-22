"""Test réel sous Windows (machine de compilation GitHub) : un raccourci enregistré par
RegisterHotKey doit se déclencher quand on « appuie » vraiment sur les touches (SendInput)."""

import ctypes
import sys
import threading
import time

import pytest

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="Windows seulement")

KEYEVENTF_KEYUP, KEYEVENTF_SCANCODE, INPUT_KEYBOARD = 0x2, 0x8, 1
VK_CONTROL, VK_MENU = 0x11, 0x12


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_ushort), ("wScan", ctypes.c_ushort), ("dwFlags", ctypes.c_uint),
                ("time", ctypes.c_uint), ("dwExtraInfo", ctypes.c_size_t)]


class INPUT(ctypes.Structure):
    class _U(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("pad", ctypes.c_byte * 32)]
    _anonymous_ = ("u",)
    _fields_ = [("type", ctypes.c_uint), ("u", _U)]


def press(*keys):
    """Appuie puis relâche les touches données : ("vk", code) ou ("sc", scan code)."""
    events = []
    for kind, code in keys:
        events.append((kind, code, 0))
    for kind, code in reversed(keys):
        events.append((kind, code, KEYEVENTF_KEYUP))
    arr = (INPUT * len(events))()
    for i, (kind, code, flags) in enumerate(events):
        arr[i].type = INPUT_KEYBOARD
        if kind == "vk":
            arr[i].ki = KEYBDINPUT(code, 0, flags, 0, 0)
        else:
            arr[i].ki = KEYBDINPUT(0, code, flags | KEYEVENTF_SCANCODE, 0, 0)
    sent = ctypes.windll.user32.SendInput(len(events), arr, ctypes.sizeof(INPUT))
    assert sent == len(events)


def wait(event, timeout=3.0):
    return event.wait(timeout)


def test_register_hotkey_fires_on_real_keypress():
    from micboard import hotkeys as hk
    from micboard.winhotkeys import WinHotkeys

    manager = WinHotkeys()
    fired = threading.Event()
    combo = hk.normalize("ctrl+alt+KeyQ")  # même touche physique que A en AZERTY
    manager.add(combo, fired.set)
    time.sleep(0.2)
    press(("vk", VK_CONTROL), ("vk", VK_MENU), ("sc", 16))
    assert wait(fired), "le raccourci Ctrl+Alt+(touche 16) ne s'est pas déclenché"

    fired.clear()
    manager.clear()
    press(("vk", VK_CONTROL), ("vk", VK_MENU), ("sc", 16))
    assert not wait(fired, 0.7), "le raccourci aurait dû être retiré"


def test_digit_row_and_function_keys():
    from micboard import hotkeys as hk
    from micboard.winhotkeys import WinHotkeys

    manager = WinHotkeys()
    got = []
    done = threading.Event()

    def hit(name):
        got.append(name)
        if len(got) == 2:
            done.set()

    manager.add(hk.normalize("ctrl+alt+Digit1"), lambda: hit("chiffre"))
    manager.add(hk.normalize("F9"), lambda: hit("f9"))
    time.sleep(0.2)
    press(("vk", VK_CONTROL), ("vk", VK_MENU), ("sc", 2))
    press(("vk", 0x78),)  # F9
    assert wait(done), f"déclenchés : {got}"
    manager.clear()
