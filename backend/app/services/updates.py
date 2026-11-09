"""Latest published app version, read from the GitHub release the CI publishes.

The release notes of the ``stable`` release contain a line ``versionCode: <n>``. The value is cached
and refreshed in a background thread, so ``/v1/config`` never waits on GitHub. If GitHub cannot be
reached the last known value (or ``FG_LATEST_VERSION_CODE``) is used.
"""

import logging
import re
import threading
import time

import httpx

from app.core.config import get_settings

log = logging.getLogger("fastgame.updates")

_REFRESH_OK_SECONDS = 600
_REFRESH_FAIL_SECONDS = 120
_lock = threading.Lock()
_value: int | None = None
_next_refresh = 0.0
_refreshing = False


def _fetch(url: str) -> int | None:
    r = httpx.get(url, timeout=httpx.Timeout(8.0, connect=5.0), headers={"Accept": "application/vnd.github+json"})
    r.raise_for_status()
    m = re.search(r"versionCode:\s*(\d+)", r.json().get("body") or "")
    return int(m.group(1)) if m else None


def _refresh(url: str) -> None:
    global _value, _next_refresh, _refreshing
    try:
        found = _fetch(url)
        with _lock:
            if found is not None:
                _value = found
            _next_refresh = time.monotonic() + (_REFRESH_OK_SECONDS if found is not None else _REFRESH_FAIL_SECONDS)
    except Exception as e:  # network trouble must never break the app config
        log.warning("update check failed: %s", e)
        with _lock:
            _next_refresh = time.monotonic() + _REFRESH_FAIL_SECONDS
    finally:
        with _lock:
            _refreshing = False


def latest_version_code() -> int:
    global _refreshing
    s = get_settings()
    fallback = s.latest_version_code
    if s.update_check_url:
        with _lock:
            due = time.monotonic() >= _next_refresh and not _refreshing
            if due:
                _refreshing = True
            current = _value
        if due:
            threading.Thread(target=_refresh, args=(s.update_check_url,), daemon=True).start()
        if current is not None:
            return max(current, fallback)
    return fallback
