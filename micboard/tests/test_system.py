import sys
import types

from micboard import system

from test_search_api import make_api


class FakeReg(types.ModuleType):
    """Faux module winreg : une seule clé, en mémoire."""
    HKEY_CURRENT_USER, KEY_SET_VALUE, REG_SZ = 1, 2, 1

    def __init__(self):
        super().__init__("winreg")
        self.values = {}

    class _Key:
        def __enter__(self): return self
        def __exit__(self, *a): return False

    def OpenKey(self, *a): return self._Key()
    def QueryValueEx(self, _k, name):
        if name not in self.values:
            raise OSError("absent")
        return self.values[name], 1
    def SetValueEx(self, _k, name, _r, _t, value): self.values[name] = value
    def DeleteValue(self, _k, name):
        if name not in self.values:
            raise FileNotFoundError(name)
        del self.values[name]


def windows_exe(monkeypatch, exe=r"C:\Jeux\MicBoard.exe"):
    reg = FakeReg()
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", exe)
    monkeypatch.setattr(system, "_winreg", lambda: reg)
    return reg


def test_autostart(monkeypatch):
    reg = windows_exe(monkeypatch)
    assert system.autostart_supported() and system.autostart_get() == ""
    system.autostart_set(True)
    assert reg.values["MicBoard"] == r'"C:\Jeux\MicBoard.exe" --tray'
    system.autostart_set(False)
    system.autostart_set(False)  # déjà retiré : pas d'erreur
    assert system.autostart_get() == ""


def test_autostart_follows_moved_exe(monkeypatch):
    reg = windows_exe(monkeypatch)
    reg.values["MicBoard"] = r'"D:\Ancien\MicBoard.exe" --tray'
    system.autostart_refresh()
    assert reg.values["MicBoard"] == r'"C:\Jeux\MicBoard.exe" --tray'


def test_autostart_only_for_the_exe(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "frozen", False, raising=False)
    assert not system.autostart_supported()
    a, _ = make_api(tmp_path, monkeypatch)
    assert "error" in a.set_autostart(True)
    assert a.devices()["autostart_supported"] is False


class FakeTray:
    available = True
    def __init__(self): self.notes, self.refreshed = [], 0
    def notify(self, m): self.notes.append(m)
    def refresh(self): self.refreshed += 1
    def stop(self): pass


class FakeWindow:
    def __init__(self): self.calls = []
    def hide(self): self.calls.append("hide")
    def show(self): self.calls.append("show")
    def restore(self): self.calls.append("restore")
    def minimize(self): self.calls.append("minimize")


def test_minimize_goes_to_tray(tmp_path, monkeypatch):
    a, _ = make_api(tmp_path, monkeypatch)
    w, t = FakeWindow(), FakeTray()
    a._attach(w)
    a._tray = t
    a.win_minimize()
    a.win_minimize()
    assert w.calls == ["hide", "hide"] and len(t.notes) == 1  # explication une seule fois
    a._show_window()
    assert w.calls[-2:] == ["show", "restore"]
    assert a.set_option("tray_minimize", False)["tray_minimize"] is False
    a.win_minimize()
    assert w.calls[-1] == "minimize"  # option coupée : barre des tâches
    a._tray = None
    a.set_option("tray_minimize", True)
    a.win_minimize()
    assert w.calls[-1] == "minimize"  # pas d'icône possible : barre des tâches aussi


def test_tray_menu_and_start_live(tmp_path, monkeypatch):
    a, _ = make_api(tmp_path, monkeypatch)
    t = FakeTray()
    a._tray = t
    a._tray_toggle_live()
    assert a._engine.running
    a._tray_toggle_live()
    assert not a._engine.running
    a.status()
    a.set_option("start_live", True)
    a._on_window_ready()
    assert a._engine.running and a.devices()["start_live"] is True
