"""Hashing, tokens, passwords and the credential vault.

All keys are derived from ``FG_SECRET_KEY`` with HMAC, so only one secret has to
be configured. Rotating it invalidates device tokens, admin tokens and stored
server credentials.
"""

import base64
import hashlib
import hmac
import secrets
from datetime import datetime, timedelta, timezone

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from cryptography.fernet import Fernet, InvalidToken

from app.core.config import get_settings

_hasher = PasswordHasher()
_DUMMY_HASH = _hasher.hash("fastgame-dummy-password")

# No 0/O/1/I so codes can be read out loud or typed from a screenshot.
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _derive(label: str) -> bytes:
    return hmac.new(get_settings().secret_key.encode(), label.encode(), hashlib.sha256).digest()


def _keyed_hash(label: str, value: str) -> str:
    return hmac.new(_derive(label), value.encode(), hashlib.sha256).hexdigest()


# --- device identity -------------------------------------------------------

def new_device_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    return _keyed_hash("device-token", token)


def hash_fingerprint(hardware_id: str) -> str:
    """Hash of the client-provided hardware id; the raw value is never stored."""
    return _keyed_hash("hardware-id", hardware_id.strip())


def new_user_code() -> str:
    raw = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(8))
    return f"{raw[:4]}-{raw[4:]}"


def strip_user_code(value: str) -> str:
    return "".join(ch for ch in value.upper() if ch.isalnum())


def format_user_code(value: str) -> str:
    raw = strip_user_code(value)
    return f"{raw[:4]}-{raw[4:]}" if len(raw) == 8 else raw


# --- admin passwords and tokens --------------------------------------------

def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, password_hash: str | None) -> bool:
    """Constant-effort check: unknown users are verified against a dummy hash."""
    try:
        ok = _hasher.verify(password_hash or _DUMMY_HASH, password)
    except (VerificationError, InvalidHashError):
        return False
    return ok and password_hash is not None


def create_admin_token(username: str) -> str:
    now = datetime.now(timezone.utc)  # real time: JWT expiry is not part of quota logic
    payload = {
        "sub": username,
        "scope": "admin",
        "iat": now,
        "exp": now + timedelta(minutes=get_settings().admin_token_minutes),
    }
    return jwt.encode(payload, _derive("admin-jwt"), algorithm="HS256")


def decode_admin_token(token: str) -> str | None:
    try:
        payload = jwt.decode(
            token, _derive("admin-jwt"), algorithms=["HS256"], options={"require": ["exp", "sub"]}
        )
    except jwt.PyJWTError:
        return None
    return payload["sub"] if payload.get("scope") == "admin" else None


# --- credential vault (OpenVPN usernames/passwords stored in the database) --

def _fernet() -> Fernet:
    return Fernet(base64.urlsafe_b64encode(_derive("vault")))


def encrypt_secret(plain: str) -> str:
    return _fernet().encrypt(plain.encode()).decode()


def decrypt_secret(token: str) -> str:
    try:
        return _fernet().decrypt(token.encode()).decode()
    except InvalidToken as exc:
        raise ValueError("stored secret cannot be decrypted (was FG_SECRET_KEY changed?)") from exc


# --- per-session VPN accounts ---------------------------------------------

def session_credentials(session_id) -> tuple[str, str]:
    """Username/password of the short-lived router account of one session.

    Derived from the master key, so nothing has to be stored: the account is found again by
    its name (``fg-`` + id) and the password is only ever handed to the app that started the
    session. The account is deleted when the session ends.
    """
    sid = str(session_id).replace("-", "")
    password = _keyed_hash("session-vpn-password", sid)[:32]
    return f"fg-{sid[:20]}", password
