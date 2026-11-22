"""Réglages communs aux tests."""

import pytest


class _NoSystemHotkeys:
    """Gestionnaire de raccourcis factice : sans lui, sous Windows, chaque appli MicBoard créée par
    un test enregistrerait de vrais raccourcis (RegisterHotKey) qui resteraient pris pour les tests
    suivants. Les vrais raccourcis Windows sont testés à part (test_windows_hotkeys.py)."""

    def clear(self):
        pass

    def add(self, combo, callback):
        pass


@pytest.fixture(autouse=True)
def no_system_hotkeys(monkeypatch):
    from micboard import api
    monkeypatch.setattr(api.Api, "_hotkey_backend",
                        lambda self: self.__dict__.setdefault("_backend", _NoSystemHotkeys()))
