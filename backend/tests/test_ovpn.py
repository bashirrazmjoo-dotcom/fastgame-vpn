"""Profile parsing, credential handling and the import CLI (synthetic profile, fake secrets)."""

import pytest
from sqlalchemy import select

from app import cli
from app.core import security
from app.core.db import SessionLocal
from app.models import VpnServer
from app.services import ovpn

SECRET_USER = "demo-user"
SECRET_PASS = "demo-pass-12345"

PROFILE = f"""client
dev tun
proto tcp
remote vpn.example.test 543
auth SHA512
<ca>
-----BEGIN CERTIFICATE-----
FAKECERTIFICATEDATA
-----END CERTIFICATE-----
</ca>
auth-user-pass
<auth-user-pass>
{SECRET_USER}
{SECRET_PASS}
</auth-user-pass>
"""


def test_inline_credentials_are_split_from_the_profile():
    p = ovpn.parse_profile(PROFILE)
    assert (p.username, p.password) == (SECRET_USER, SECRET_PASS)
    assert (p.host, p.port, p.proto) == ("vpn.example.test", 543, "tcp")
    assert SECRET_PASS not in p.config and "<auth-user-pass>" not in p.config
    assert "FAKECERTIFICATEDATA" in p.config  # the rest of the profile is untouched
    assert p.config.count("auth-user-pass") == 1  # bare directive kept so clients ask for credentials


def test_bare_directive_is_added_when_only_a_block_is_present():
    text = "client\nremote a.example.test 1194 udp\n<auth-user-pass>\nu\np\n</auth-user-pass>\n"
    p = ovpn.parse_profile(text)
    assert (p.username, p.password) == ("u", "p")
    assert p.config.rstrip().endswith("auth-user-pass") and "<auth-user-pass>" not in p.config


def test_profile_without_credentials_is_unchanged():
    text = "client\nremote a.example.test 1194\nauth-user-pass\n"
    p = ovpn.parse_profile(text)
    assert p.config == text and p.username is None and p.password is None


@pytest.mark.parametrize(
    "text,expected",
    [
        ("client\nremote a.test\n", ("a.test", 1194, "udp")),  # defaults
        ("client\nproto tcp-client\nremote a.test 443\n", ("a.test", 443, "tcp")),
        ("client\nproto udp\nremote a.test 443 tcp\n", ("a.test", 443, "tcp")),  # remote wins
        ("client\nproto udp6\nremote a.test 1195\n", ("a.test", 1195, "udp")),
    ],
)
def test_remote_and_proto_parsing(text, expected):
    p = ovpn.parse_profile(text)
    assert (p.host, p.port, p.proto) == expected


def test_check_profile_rejects_dangerous_directives():
    with pytest.raises(ValueError, match="forbidden"):
        ovpn.check_profile("client\nremote a.test 1194\nup /tmp/x.sh\n")
    with pytest.raises(ValueError, match="remote"):
        ovpn.check_profile("client\ndev tun\n")
    ovpn.check_profile(PROFILE)  # the normal profile passes


def test_admin_api_derives_fields_and_encrypts_credentials(api, client, admin_headers):
    r = client.post(
        "/admin/servers",
        json={
            "name": "Germany 1",
            "country_code": "de",
            "country_name_en": "Germany",
            "country_name_fa": "آلمان",
            "ovpn_config": PROFILE,
        },
        headers=admin_headers,
    )
    assert r.status_code == 201, r.text
    s = r.json()
    assert (s["host"], s["port"], s["proto"]) == ("vpn.example.test", 543, "tcp")
    assert s["has_credentials"] is True and s["auth_username"] == SECRET_USER
    assert SECRET_PASS not in r.text and SECRET_PASS not in s["ovpn_config"]

    with SessionLocal() as db:
        row = db.execute(select(VpnServer).where(VpnServer.id == s["id"])).scalar_one()
    assert SECRET_PASS not in (row.auth_password_enc or "") and SECRET_PASS not in row.ovpn_config
    assert security.decrypt_secret(row.auth_password_enc) == SECRET_PASS

    # The app receives the credentials only when a session starts.
    dev = api.register()
    started = api.c.post("/v1/sessions", json={"server_id": s["id"]}, headers=dev.headers).json()
    assert started["auth_username"] == SECRET_USER and started["auth_password"] == SECRET_PASS
    assert SECRET_PASS not in started["ovpn_config"]


def test_incomplete_profile_without_remote_is_rejected(client, admin_headers):
    r = client.post(
        "/admin/servers",
        json={
            "name": "Broken", "country_code": "DE", "country_name_en": "Germany",
            "country_name_fa": "آلمان", "ovpn_config": "client\ndev tun\nproto tcp\n",
        },
        headers=admin_headers,
    )
    assert r.status_code == 422


def test_cli_import_is_an_upsert(tmp_path, capsys):
    f = tmp_path / "de1.ovpn"
    f.write_text(PROFILE, encoding="utf-8")
    argv = ["import-server", "--file", str(f), "--name", "Germany 1", "--country", "de",
            "--country-en", "Germany", "--country-fa", "آلمان"]
    assert cli.main(argv) == 0
    assert cli.main(argv) == 0  # second run updates instead of duplicating
    out = capsys.readouterr().out
    assert "created" in out and "updated" in out
    assert SECRET_PASS not in out  # credentials are never printed

    with SessionLocal() as db:
        rows = list(db.execute(select(VpnServer)).scalars())
    assert len(rows) == 1 and rows[0].host == "vpn.example.test" and rows[0].country_code == "DE"
    assert security.decrypt_secret(rows[0].auth_password_enc) == SECRET_PASS


def test_cli_rejects_bad_files(tmp_path, capsys):
    bad = tmp_path / "bad.ovpn"
    bad.write_text("client\nremote a.test 1194\nscript-security 2\n", encoding="utf-8")
    code = cli.main(["import-server", "--file", str(bad), "--name", "X", "--country", "DE",
                     "--country-en", "Germany", "--country-fa", "آلمان"])
    assert code == 1 and "forbidden" in capsys.readouterr().err
    assert cli.main(["import-server", "--file", str(tmp_path / "missing.ovpn"), "--name", "X",
                     "--country", "DE", "--country-en", "G", "--country-fa", "ا"]) == 1


def test_cli_create_admin_and_login(client, capsys):
    assert cli.main(["create-admin", "--username", "boss", "--password", "long-enough-pass-1"]) == 0
    ok = client.post("/admin/auth/login", json={"username": "boss", "password": "long-enough-pass-1"})
    assert ok.status_code == 200
    assert cli.main(["create-admin", "--username", "boss", "--password", "short"]) == 1
    assert cli.main(["create-admin", "--username", "boss", "--password", "another-long-pass-2"]) == 0
    assert "password reset" in capsys.readouterr().out
