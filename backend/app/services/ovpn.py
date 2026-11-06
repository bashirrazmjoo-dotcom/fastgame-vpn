"""Parsing and safety checks for .ovpn profiles (pure functions, no database)."""

import re
from dataclasses import dataclass

# Directives that make a client run programs or hand control elsewhere. A profile
# containing them is rejected so a bad paste can never turn into code execution
# on a user's phone.
FORBIDDEN_DIRECTIVES = frozenset(
    {
        "up", "down", "route-up", "route-pre-down", "ipchange", "client-connect",
        "client-disconnect", "learn-address", "tls-verify", "auth-user-pass-verify",
        "plugin", "script-security", "management", "setenv-safe",
    }
)

_AUTH_BLOCK = re.compile(r"[ \t]*<auth-user-pass>\s*(.*?)\s*</auth-user-pass>[ \t]*\r?\n?", re.S | re.I)
_BARE_AUTH = re.compile(r"^\s*auth-user-pass\s*(?:#.*)?$", re.I | re.M)
_REMOTE = re.compile(r"^\s*remote\s+(\S+)(?:\s+(\d+))?(?:\s+(\S+))?", re.I | re.M)
_PROTO = re.compile(r"^\s*proto\s+(\S+)", re.I | re.M)


@dataclass(frozen=True)
class ParsedProfile:
    config: str  # the profile with inline credentials removed
    username: str | None
    password: str | None
    host: str | None
    port: int | None
    proto: str | None  # "udp" | "tcp"


def directives(text: str) -> set[str]:
    """First word of every directive line (comments and <tag> lines skipped)."""
    found: set[str] = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line[0] in "#;<":
            continue
        found.add(re.split(r"\s+", line, maxsplit=1)[0].lower())
    return found


def check_profile(text: str) -> None:
    """Raise ValueError if the profile is unsafe or unusable."""
    found = directives(text)
    bad = sorted(found & FORBIDDEN_DIRECTIVES)
    if bad:
        raise ValueError(f"forbidden OpenVPN directive(s): {', '.join(bad)}")
    if "remote" not in found:
        raise ValueError("the profile must contain a 'remote' line")


def _normalize_proto(value: str | None) -> str | None:
    if not value:
        return None
    value = value.lower()
    if value.startswith("tcp"):
        return "tcp"
    if value.startswith("udp"):
        return "udp"
    return None


def parse_profile(text: str) -> ParsedProfile:
    """Split a profile into (profile without secrets, username, password, host, port, proto).

    Credentials given inline in an ``<auth-user-pass>`` block are removed from the
    returned config so they can be stored encrypted; the bare ``auth-user-pass``
    directive is kept (or added) so clients know to supply them.
    """
    config = text
    username = password = None

    block = _AUTH_BLOCK.search(text)
    if block:
        lines = [line.strip() for line in block.group(1).splitlines() if line.strip()]
        if len(lines) >= 2:
            username, password = lines[0], lines[1]
            config = _AUTH_BLOCK.sub("", text)
            if not _BARE_AUTH.search(config):
                config = config.rstrip("\n") + "\nauth-user-pass\n"

    host = port = proto = None
    remote = _REMOTE.search(config)
    if remote:
        host = remote.group(1)
        port = int(remote.group(2)) if remote.group(2) else 1194
        proto = _normalize_proto(remote.group(3))
    if proto is None:
        proto_line = _PROTO.search(config)
        proto = _normalize_proto(proto_line.group(1)) if proto_line else ("udp" if remote else None)

    return ParsedProfile(config, username, password, host, port, proto)
