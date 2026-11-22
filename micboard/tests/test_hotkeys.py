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


def test_hotkeys_really_register(tmp_path, monkeypatch):
    """Reproduit keyboard.add_hotkey : le raccourci sert de clé de dictionnaire (une liste plantait)."""
    import keyboard
    registered = {}

    def fake_add_hotkey(hotkey, callback, args=(), **_kw):
        # la vraie fonction finit par « _hotkeys[hotkey] = … » : TypeError si ce n'est pas hachable
        registered[hotkey] = (callback, args)

    monkeypatch.setattr(keyboard, "add_hotkey", fake_add_hotkey)
    monkeypatch.setattr(keyboard, "unhook_all_hotkeys", lambda: registered.clear())
    a, root = make_api(tmp_path, monkeypatch)
    go = str(root / "Counter-Strike Source" / "radio" / "go.wav")
    a.set_hotkey(go, "ctrl+alt+Digit1", "Ctrl + Alt + 1")  # Ctrl + Alt + & en AZERTY
    a.set_stop_hotkey("shift+Numpad0", "Maj + Num 0")
    assert ("ctrl", "alt", 2) in registered and ("shift", 82) in registered
    callback, args = registered[("ctrl", "alt", 2)]
    assert args == (go,)
    assert a.status()["message"] == ""  # aucun échec signalé


def test_hotkey_failure_is_reported(tmp_path, monkeypatch):
    import keyboard

    def broken(*_a, **_kw):
        raise ValueError("touche inconnue")

    monkeypatch.setattr(keyboard, "add_hotkey", broken)
    monkeypatch.setattr(keyboard, "unhook_all_hotkeys", lambda: None)
    a, root = make_api(tmp_path, monkeypatch)
    a.set_hotkey(str(root / "Mes sons" / "klaxon.wav"), "f7", "F7")
    assert "F7" in a.status()["message"]
