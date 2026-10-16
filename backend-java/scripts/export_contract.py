"""Export immutable main-baseline contracts; never connect to a database.

Run with the existing backend development virtualenv from repository root.
"""
import enum
import json
import sys
from pathlib import Path

from sqlalchemy import create_mock_engine

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
from app import models
from app.main import app
from app.services.admin_settings import SETTING_DEFAULTS
from app.product_schemas import ScenarioPolicy
from sqlmodel import SQLModel

OUT = ROOT / "backend-java/src/main/resources/contracts"
OUT.mkdir(parents=True, exist_ok=True)
(OUT / "scenario-defaults.json").write_text(ScenarioPolicy().model_dump_json(indent=2))
(OUT / "setting-defaults.json").write_text(json.dumps(SETTING_DEFAULTS, ensure_ascii=False, indent=2))
(OUT / "openapi-baseline.json").write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2))
metadata = {}
for name, table in SQLModel.metadata.tables.items():
    columns = {}
    for col in table.columns:
        info = {"type": str(col.type), "nullable": col.nullable, "primary_key": col.primary_key}
        if hasattr(col.type, "enums"):
            info["enum"] = dict(zip(col.type.enums, [x.value for x in col.type.enum_class]))
        if col.default is not None:
            default = col.default.arg
            if callable(default):
                info["factory"] = "uuid" if "uuid" in default.__name__ else "now"
            else:
                info["default"] = default.value if isinstance(default, enum.Enum) else default
        columns[col.name] = info
    metadata[name] = columns
(OUT / "tables.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
for dialect in ["postgresql", "sqlite"]:
    ddl = []
    engine = create_mock_engine(dialect + "://", lambda sql, *a, **kw: ddl.append(str(sql.compile(dialect=engine.dialect)) + ";"))
    SQLModel.metadata.create_all(engine)
    normalized = "\n".join(line.rstrip() for line in "\n".join(ddl).splitlines()) + "\n"
    (OUT / ("schema-" + dialect + ".sql")).write_text(normalized)
routes = []
for path, operations in app.openapi()["paths"].items():
    for method in operations:
        if method in {"get", "post", "put", "patch", "delete"}:
            routes.append({"method": method.upper(), "path": path, "java_status": "pending"})
(OUT / "migration-inventory.json").write_text(json.dumps({"baseline": "96d9bef44173612d85399792bf54c6399059b1e7", "routes": routes}, indent=2))
print(f"Exported {len(metadata)} tables and {len(routes)} routes without connecting to a database")
from sync_inventory import update_inventory
update_inventory()
