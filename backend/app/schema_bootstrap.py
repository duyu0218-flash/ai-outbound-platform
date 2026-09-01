"""Explicit database bootstrap entrypoint for a controlled release step."""

from .db import create_db_and_tables


if __name__ == "__main__":
    create_db_and_tables(force=True)
    print("database schema bootstrap completed")
