from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path

import psycopg

from .config import get_settings


def _migration_directory() -> Path:
    configured = os.getenv("MIGRATIONS_DIR", "").strip()
    if configured:
        return Path(configured)
    container_path = Path("/app/migrations/postgresql")
    if container_path.is_dir():
        return container_path
    return Path(__file__).resolve().parents[1] / "migrations" / "postgresql"


def _psycopg_dsn(url: str) -> str:
    return url.replace("postgresql+psycopg://", "postgresql://", 1)


def apply_postgres_migrations() -> list[str]:
    settings = get_settings()
    if not settings.database_url.startswith(("postgresql://", "postgresql+psycopg://")):
        raise RuntimeError("versioned migrations require a PostgreSQL DATABASE_URL")
    directory = _migration_directory()
    files = sorted(directory.glob("*.sql"))
    if not files:
        raise RuntimeError(f"no migration files found in {directory}")
    applied: list[str] = []
    with psycopg.connect(_psycopg_dsn(settings.database_url)) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT pg_advisory_lock(hashtext('ai-outbound-schema-migrations'))")
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migration_history (
                    version VARCHAR(255) PRIMARY KEY,
                    checksum_sha256 VARCHAR(64) NOT NULL,
                    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.commit()
            for path in files:
                version = path.name
                source = path.read_text(encoding="utf-8")
                checksum = hashlib.sha256(source.encode()).hexdigest()
                cursor.execute(
                    "SELECT checksum_sha256 FROM schema_migration_history WHERE version = %s",
                    (version,),
                )
                row = cursor.fetchone()
                if row is not None:
                    if row[0] != checksum:
                        raise RuntimeError(f"checksum changed for applied migration: {version}")
                    continue
                body = re.sub(r"(?im)^\s*BEGIN\s*;\s*$", "", source, count=1)
                body = re.sub(r"(?im)^\s*COMMIT\s*;\s*$", "", body, count=1)
                with connection.transaction():
                    cursor.execute(body)
                    cursor.execute(
                        "INSERT INTO schema_migration_history(version, checksum_sha256) VALUES (%s, %s)",
                        (version, checksum),
                    )
                applied.append(version)
            cursor.execute("SELECT pg_advisory_unlock(hashtext('ai-outbound-schema-migrations'))")
    return applied


if __name__ == "__main__":
    versions = apply_postgres_migrations()
    print("applied migrations:", ", ".join(versions) if versions else "none")
