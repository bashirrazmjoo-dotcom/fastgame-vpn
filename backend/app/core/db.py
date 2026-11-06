"""SQLAlchemy engine, session factory and declarative base."""

from sqlalchemy import MetaData, create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from app.core.config import get_settings

NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


engine = create_engine(get_settings().database_url, pool_pre_ping=True, pool_size=10, max_overflow=20,
                       connect_args={"options": "-c timezone=UTC"})

# Services commit explicitly; nothing is persisted implicitly.
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)
