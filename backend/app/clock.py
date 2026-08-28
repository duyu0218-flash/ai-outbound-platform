from datetime import UTC, datetime


def utc_now() -> datetime:
    """Return naive UTC for compatibility with the existing database schema."""
    return datetime.now(UTC).replace(tzinfo=None)
