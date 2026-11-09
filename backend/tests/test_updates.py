from app.services import updates


def test_parses_version_code_from_release_notes(monkeypatch):
    class R:
        def raise_for_status(self): ...
        def json(self): return {"body": "Build abc1234\nversionCode: 142\n"}

    monkeypatch.setattr(updates.httpx, "get", lambda *a, **k: R())
    assert updates._fetch("https://example.test") == 142


def test_missing_version_line_is_none(monkeypatch):
    class R:
        def raise_for_status(self): ...
        def json(self): return {"body": "no version here"}

    monkeypatch.setattr(updates.httpx, "get", lambda *a, **k: R())
    assert updates._fetch("https://example.test") is None
