"""The single source of time for the whole backend.

Every quota/VIP calculation goes through ``utcnow`` so the server clock is the
only clock that matters, and tests can replace it with a controllable one.
Always call it as ``clock.utcnow()`` (never ``from ... import utcnow``).
"""

from datetime import datetime, timezone


def utcnow() -> datetime:
    return datetime.now(timezone.utc)
