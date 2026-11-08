def test_privacy_page_is_public_and_bilingual(client):
    r = client.get("/privacy")
    assert r.status_code == 200
    assert "text/html" in r.headers["content-type"]
    assert "Privacy Policy" in r.text and "حریم خصوصی" in r.text
    assert "VpnService" in r.text
