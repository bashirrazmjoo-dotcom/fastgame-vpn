from fastapi import APIRouter, Depends, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import client_ip, get_current_admin, get_db
from app.core import clock, security
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.ratelimit import limiter
from app.models.admin import AdminUser
from app.schemas.admin import AdminLoginRequest, AdminLoginResponse, AdminMeResponse

router = APIRouter(prefix="/admin/auth", tags=["admin"])


@router.post("/login", response_model=AdminLoginResponse)
def login(body: AdminLoginRequest, request: Request, db: Session = Depends(get_db)) -> AdminLoginResponse:
    s = get_settings()
    if not limiter.allow(f"admin-login:{client_ip(request)}", s.admin_login_limit_per_minute, 60):
        raise ApiError(429, "rate_limited", "Too many login attempts. Try again in a minute.")

    admin = db.execute(select(AdminUser).where(AdminUser.username == body.username)).scalar_one_or_none()
    usable_hash = admin.password_hash if admin is not None and admin.is_active else None
    if not security.verify_password(body.password, usable_hash):
        raise ApiError(401, "invalid_credentials", "Invalid username or password.")

    admin.last_login_at = clock.utcnow()
    db.commit()
    return AdminLoginResponse(
        access_token=security.create_admin_token(admin.username), expires_in=s.admin_token_minutes * 60
    )


@router.get("/me", response_model=AdminMeResponse)
def me(admin: AdminUser = Depends(get_current_admin)) -> AdminMeResponse:
    return AdminMeResponse(username=admin.username)
