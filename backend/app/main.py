"""FastGame VPN API entry point."""

import asyncio
import logging
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import select, text

from app.api import (
    admin_auth,
    admin_devices,
    admin_servers,
    admin_stats,
    bot_api,
    v1_device,
    v1_sessions,
)
from app.core import security
from app.core.config import get_settings
from app.core.db import SessionLocal
from app.core.errors import ApiError
from app.models.admin import AdminUser
from app.services import credentials, sessions

log = logging.getLogger("fastgame")


def _bootstrap_admin() -> None:
    """Create the first admin from FG_ADMIN_BOOTSTRAP_* when no admin exists yet."""
    s = get_settings()
    if not (s.admin_bootstrap_username and s.admin_bootstrap_password):
        return
    with SessionLocal() as db:
        if db.scalar(select(AdminUser.id).limit(1)) is None:
            db.add(
                AdminUser(
                    username=s.admin_bootstrap_username,
                    password_hash=security.hash_password(s.admin_bootstrap_password),
                )
            )
            db.commit()
            log.info("created bootstrap admin %r", s.admin_bootstrap_username)


def _reap_once() -> None:
    with SessionLocal() as db:
        sessions.reap_stale_sessions(db)
        credentials.sweep(db)


async def _reaper_loop() -> None:
    interval = get_settings().reaper_interval_seconds
    while True:
        await asyncio.sleep(interval)
        try:
            await asyncio.to_thread(_reap_once)
        except Exception:  # keep the loop alive whatever happens
            log.exception("session reaper failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    await asyncio.to_thread(_bootstrap_admin)
    task = asyncio.create_task(_reaper_loop())
    try:
        yield
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task


settings = get_settings()
app = FastAPI(
    title="FastGame VPN API",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs" if settings.is_dev else None,
    redoc_url=None,
    openapi_url="/openapi.json" if settings.is_dev else None,
)

if settings.cors_origin_list:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_methods=["GET", "POST", "PUT", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
    )


@app.exception_handler(ApiError)
async def _api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content=exc.body())


@app.exception_handler(RequestValidationError)
async def _validation_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    details = [{"loc": list(e["loc"]), "msg": e["msg"], "type": e["type"]} for e in exc.errors()]
    return JSONResponse(
        status_code=422,
        content={"error": {"code": "validation_error", "message": "Invalid request.", "details": {"errors": details}}},
    )


@app.get("/healthz", tags=["ops"])
def healthz() -> dict[str, str]:
    with SessionLocal() as db:
        db.execute(text("SELECT 1"))
    return {"status": "ok"}


app.include_router(v1_device.router)
app.include_router(v1_sessions.router)
app.include_router(admin_auth.router)
app.include_router(admin_devices.router)
app.include_router(admin_servers.router)
app.include_router(admin_stats.router)
app.include_router(bot_api.router)
