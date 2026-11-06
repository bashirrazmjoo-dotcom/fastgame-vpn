"""Operational commands. Run from the backend directory::

    python -m app.cli import-server --file germany1.ovpn --name "Germany 1" \\
        --country DE --country-en Germany --country-fa آلمان
    python -m app.cli create-admin --username admin

Credentials are never printed. Inline OpenVPN credentials are stored encrypted.
"""

import argparse
import getpass
import sys
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import security
from app.core.db import SessionLocal
from app.models import AdminUser, VpnServer
from app.services import ovpn


def upsert_server(
    db: Session,
    *,
    profile_text: str,
    name: str,
    country_code: str,
    country_name_en: str,
    country_name_fa: str,
    sort_order: int = 0,
) -> tuple[VpnServer, bool]:
    """Create the server, or update the one with the same name. Returns (server, created)."""
    ovpn.check_profile(profile_text)
    parsed = ovpn.parse_profile(profile_text)
    if not (parsed.host and parsed.port and parsed.proto):
        raise ValueError("could not read host, port and protocol from the profile")

    existing = db.execute(select(VpnServer).where(VpnServer.name == name)).scalar_one_or_none()
    server = existing or VpnServer()
    server.name = name
    server.country_code = country_code.upper()
    server.country_name_en = country_name_en
    server.country_name_fa = country_name_fa
    server.host, server.port, server.proto = parsed.host, parsed.port, parsed.proto
    server.ovpn_config = parsed.config
    server.sort_order = sort_order
    if parsed.username and parsed.password:
        server.auth_username = parsed.username
        server.auth_password_enc = security.encrypt_secret(parsed.password)
    if existing is None:
        db.add(server)
    db.commit()
    return server, existing is None


def upsert_admin(db: Session, *, username: str, password: str) -> bool:
    """Create the admin, or reset the password of an existing one. Returns True if created."""
    admin = db.execute(select(AdminUser).where(AdminUser.username == username)).scalar_one_or_none()
    created = admin is None
    if admin is None:
        admin = AdminUser(username=username, password_hash=security.hash_password(password))
        db.add(admin)
    else:
        admin.password_hash = security.hash_password(password)
        admin.is_active = True
    db.commit()
    return created


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="command", required=True)

    imp = sub.add_parser("import-server", help="add or update an OpenVPN server from a .ovpn file")
    imp.add_argument("--file", required=True, type=Path)
    imp.add_argument("--name", required=True)
    imp.add_argument("--country", required=True, help="ISO 3166-1 alpha-2, e.g. DE")
    imp.add_argument("--country-en", required=True)
    imp.add_argument("--country-fa", required=True)
    imp.add_argument("--sort-order", type=int, default=0)

    adm = sub.add_parser("create-admin", help="create an admin user or reset its password")
    adm.add_argument("--username", required=True)
    adm.add_argument("--password", help="omit to be prompted (recommended)")

    args = parser.parse_args(argv)

    if args.command == "import-server":
        try:
            text = args.file.read_text(encoding="utf-8")
            with SessionLocal() as db:
                server, created = upsert_server(
                    db,
                    profile_text=text,
                    name=args.name,
                    country_code=args.country,
                    country_name_en=args.country_en,
                    country_name_fa=args.country_fa,
                    sort_order=args.sort_order,
                )
        except (OSError, ValueError) as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1
        creds = "stored encrypted" if server.auth_password_enc else "none"
        verb = "created" if created else "updated"
        print(f"{verb} server #{server.id} {server.name!r} -> {server.host}:{server.port}/{server.proto}; credentials: {creds}")
        return 0

    password = args.password or getpass.getpass("Admin password: ")
    if len(password) < 10:
        print("error: password must be at least 10 characters", file=sys.stderr)
        return 1
    with SessionLocal() as db:
        created = upsert_admin(db, username=args.username, password=password)
    print(f"admin {args.username!r} {'created' if created else 'password reset'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
