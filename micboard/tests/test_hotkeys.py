import json

from micboard import api as apimod
from micboard import hotkeys as hk

from test_search_api import FakeEngine, make_api, touch_wav


def test_physical_keys_become_scan_codes():
    # touche A d'un clavier AZERTY = position « KeyQ » : on enregistre le code matériel 16
    assert hk.normalize("ctrl+KeyQ") == "ctrl+#16"
    assert hk.normalize("alt+ctrl+KeyA") == "ctrl+alt+#30"  # modificateurs toujours dans le même ordre
    assert hk.normalize("Digit1") == "#2"
    assert hk.normalize("shift+Numpad1") == "shift+#79"
    assert hk.normalize("F5") == "f5" and hk.normalize("space") == "space"
    assert hk.for_keyboard("ctrl+#16") == ("ctrl", 16)
    hash(hk.for_keyboard("ctrl+alt+#2"))  # doit pouvoir servir de clé (voir keyboard.add_hotkey)


def test_labels():
    assert hk.label("f5") == "F5"
    assert hk.label("shift+#79") == "Maj + Num 1"
    assert hk.label("#2") == "1"  # chiffre, même si la touche donne « & » en AZERTY
    assert hk.label("ctrl+up") == "Ctrl + ↑"
    assert hk.label("ctrl+#30").startswith("Ctrl + ")
    assert hk.label("") == ""


def test_old_qwerty_hotkeys_are_repaired():
    assert hk.migrate("ctrl+q") == "ctrl+#16"  # était la touche physique « KeyQ »
    assert hk.migrate("num 3") == "#81"
    assert hk.migrate("f6") == "f6"


def test_keyboard_module_accepts_the_format():
    import keyboard
    assert keyboard.parse_hotkey([16]) == (((16,),),)
    assert keyboard.parse_hotkey([2, 30]) == (((2,), (30,)),)


def test_api_hotkeys(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    go = str(root / "Counter-Strike Source" / "radio" / "go.wav")
    label = a.set_hotkey(go, "ctrl+KeyQ")
    assert label.startswith("Ctrl + ")
    assert a._hotkeys[go] == "ctrl+#16"
    assert a.search("go")["results"][0]["hotkey"] == label
    assert a.set_stop_hotkey("ctrl+KeyQ").startswith("Ctrl")  # même raccourci : retiré du son
    assert go not in a._hotkeys
    assert a.devices()["stop_hotkey"].startswith("Ctrl")


def test_old_config_is_migrated(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    go = str(root / "Counter-Strike Source" / "radio" / "go.wav")
    cfg = tmp_path / "cfg.json"
    data = json.loads(cfg.read_text())
    data.update(hotkeys={go: "ctrl+q"}, stop_hotkey="f9")
    data.pop("hotkeys_version", None)
    cfg.write_text(json.dumps(data))
    b = apimod.Api(engine=FakeEngine(), cfg_file=cfg)
    assert b._hotkeys[go] == "ctrl+#16" and b._cfg["stop_hotkey"] == "f9"
    b._save()
    c = apimod.Api(engine=FakeEngine(), cfg_file=cfg)  # pas migré une seconde fois
    assert c._hotkeys[go] == "ctrl+#16"


def test_captured_label_used_when_layout_unknown(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    go = str(root / "Counter-Strike Source" / "radio" / "go.wav")
    # hors de Windows, la disposition est inconnue : on garde le nom vu à la capture (AZERTY)
    monkeypatch.setattr(hk, "key_char", lambda sc: "")
    assert a.set_hotkey(go, "ctrl+KeyQ", "Ctrl + A") == "Ctrl + A"
    assert a.search("go")["results"][0]["hotkey"] == "Ctrl + A"
    # quand Windows connaît la disposition, c'est elle qui compte
    monkeypatch.setattr(hk, "key_char", lambda sc: "a" if sc == 16 else "")
    assert a.search("go")["results"][0]["hotkey"] == "Ctrl + A"
    assert hk.label("f5", "Ctrl + A") == "F5"  # pas de code matériel : nom calculé


class FakeBackend:
    """Remplace RegisterHotKey : garde les raccourcis pour pouvoir les « appuyer »."""
    def __init__(self, refuse=()):
        self.keys, self.refuse = {}, set(refuse)
    def clear(self):
        self.keys.clear()
    def add(self, combo, callback):
        if combo in self.refuse:
            raise RuntimeError("déjà utilisé par une autre application")
        self.keys[combo] = callback
    def press(self, combo):
        self.keys[combo]()


def test_hotkey_plays_the_sound(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    backend = FakeBackend()
    a._backend = backend
    go = str(root / "Counter-Strike Source" / "radio" / "go.wav")
    a.set_hotkey(go, "ctrl+alt+KeyQ", "Ctrl + Alt + A")  # Ctrl + Alt + A en AZERTY
    a.set_stop_hotkey("shift+Numpad0", "Maj + Num 0")
    assert set(backend.keys) == {"ctrl+alt+#16", "shift+#82"}

    backend.press("ctrl+alt+#16")  # micro hors ligne : rien ne joue, mais on le dit
    assert a._mixer.active() == [] and "Active" in a.status()["message"]
    a.start()
    backend.press("ctrl+alt+#16")
    assert [v["tag"] for v in a._mixer.active()] == [go]
    backend.press("shift+#82")  # tout arrêter
    assert a._mixer.active() == []


def test_refused_hotkey_is_explained(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    a._backend = FakeBackend(refuse={"ctrl+alt+#16"})
    a.set_hotkey(str(root / "Mes sons" / "klaxon.wav"), "ctrl+alt+KeyQ", "Ctrl + Alt + A")
    msg = a.status()["message"]
    assert "Ctrl + Alt + A" in msg and "autre application" in msg


def test_combo_to_windows_keys():
    from micboard.winhotkeys import MOD_ALT, MOD_CONTROL, MOD_SHIFT, HotkeyError, combo_to_vk
    azerty = {16: 0x41, 30: 0x51, 2: 0x31}  # AZERTY : la touche de scan code 16 est A
    conv = lambda c: combo_to_vk(c, azerty.get, lambda ch: ord(ch.upper()))
    assert conv("ctrl+alt+#16") == (MOD_CONTROL | MOD_ALT, 0x41)
    assert conv("ctrl+alt+#2") == (MOD_CONTROL | MOD_ALT, 0x31)  # touche &/1
    assert conv("f5") == (0, 0x74)
    assert conv("shift+#79") == (MOD_SHIFT, 0x61)  # Num 1
    assert conv("up") == (0, 0x26)
    import pytest
    with pytest.raises(HotkeyError):
        conv("ctrl+alt")
    with pytest.raises(HotkeyError):
        conv("ctrl+n'importe quoi")
