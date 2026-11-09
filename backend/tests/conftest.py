"""Test harness: real PostgreSQL, migrations applied, controllable server clock."""

import os
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Environment must be set before the application modules are imported.
TEST_DB = "fastgame_test"
os.environ["FG_SECRET_KEY"] = "test-secret-key-for-fastgame-0123456789"
os.environ["FG_ENV"] = "development"
os.environ["FG_UPDATE_CHECK_URL"] = ""  # tests never call GitHub
os.environ["FG_DATABASE_URL"] = f"postgresql+psycopg://fastgame:fastgame@127.0.0.1:5432/{TEST_DB}"
for _name in ("FG_ADMIN_BOOTSTRAP_USERNAME", "FG_ADMIN_BOOTSTRAP_PASSWORD"):
    os.environ.pop(_name, None)

import psycopg  # noqa: E402
import pytest  # noqa: E402
from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import text  # noqa: E402

from app.core import security  # noqa: E402
from app.core.db import SessionLocal, engine  # noqa: E402
from app.core.ratelimit import limiter  # noqa: E402
from app.main import app  # noqa: E402
from app.models import AdminUser, VpnServer  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ADMIN_USER = "admin"
ADMIN_PASSWORD = "correct-horse-battery"


@pytest.fixture(scope="session", autouse=True)
def _database():
    with psycopg.connect("postgresql://fastgame:fastgame@127.0.0.1:5432/postgres", autocommit=True) as conn:
        conn.execute(f"DROP DATABASE IF EXISTS {TEST_DB} WITH (FORCE)")
        conn.execute(f"CREATE DATABASE {TEST_DB}")
    cfg = Config(str(ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(ROOT / "alembic"))
    command.upgrade(cfg, "head")  # the real migration, not create_all
    yield
    engine.dispose()


@pytest.fixture(autouse=True)
def _clean_state():
    yield
    with engine.begin() as conn:
        conn.execute(text("TRUNCATE devices, vpn_servers, admin_users RESTART IDENTITY CASCADE"))
    limiter.reset()


class FakeClock:
    def __init__(self) -> None:
        self.now = datetime(2026, 11, 4, 12, 0, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += timedelta(seconds=seconds)


@pytest.fixture
def clock(monkeypatch) -> FakeClock:
    fake = FakeClock()
    monkeypatch.setattr("app.core.clock.utcnow", fake)
    return fake


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture
def server_id() -> int:
    with SessionLocal() as db:
        server = VpnServer(
            name="Frankfurt 1",
            country_code="DE",
            country_name_en="Germany",
            country_name_fa="آلمان",
            host="de1.example.test",
            port=1194,
            proto="udp",
            ovpn_config="client\nremote de1.example.test 1194 udp\n",
            auth_username="vpnuser",
            auth_password_enc=security.encrypt_secret("vpnpass"),
        )
        db.add(server)
        db.commit()
        return server.id


@pytest.fixture
def admin_headers(client) -> dict[str, str]:
    with SessionLocal() as db:
        db.add(AdminUser(username=ADMIN_USER, password_hash=security.hash_password(ADMIN_PASSWORD)))
        db.commit()
    r = client.post("/admin/auth/login", json={"username": ADMIN_USER, "password": ADMIN_PASSWORD})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


@dataclass
class Dev:
    code: str
    token: str
    headers: dict[str, str]


class Api:
    """Thin helper around the app-facing endpoints."""

    def __init__(self, client: TestClient, server_id: int) -> None:
        self.c = client
        self.server_id = server_id

    def register(self, hardware_id: str = "hardware-id-0001") -> Dev:
        r = self.c.post(
            "/v1/devices/register",
            json={"hardware_id": hardware_id, "app_version_code": 1, "locale": "fa"},
        )
        assert r.status_code == 200, r.text
        body = r.json()
        return Dev(body["user_code"], body["device_token"], {"Authorization": f"Bearer {body['device_token']}"})

    def me(self, dev: Dev):
        return self.c.get("/v1/me", headers=dev.headers)

    def start(self, dev: Dev):
        return self.c.post("/v1/sessions", json={"server_id": self.server_id}, headers=dev.headers)

    def heartbeat(self, dev: Dev, session_id: str):
        return self.c.post(f"/v1/sessions/{session_id}/heartbeat", headers=dev.headers)

    def stop(self, dev: Dev, session_id: str):
        return self.c.post(f"/v1/sessions/{session_id}/stop", headers=dev.headers)

    def connected(self, dev: Dev, session_id: str):
        return self.c.post(f"/v1/sessions/{session_id}/connected", headers=dev.headers)

    def connect(self, dev: Dev) -> dict:
        """What the app does on a successful connection: start, then confirm the tunnel is up."""
        started = self.start(dev)
        assert started.status_code == 201, started.text
        body = started.json()
        confirmed = self.connected(dev, body["session_id"])
        assert confirmed.status_code == 200, confirmed.text
        return body


@pytest.fixture
def api(client, server_id) -> Api:
    return Api(client, server_id)
