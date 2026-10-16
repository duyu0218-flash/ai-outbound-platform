"""Freeze the existing Python migration sources without opening any database."""
import hashlib
import json
import sys
from contextlib import contextmanager
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend'))
from app import schema_migrations as runtime
from app import models
from sqlmodel import SQLModel
OUT = ROOT / 'backend-java/src/main/resources/migrations'
(OUT / 'postgresql').mkdir(parents=True, exist_ok=True)
files = []
for source in sorted((ROOT / 'backend/migrations/postgresql').glob('*.sql')):
    data = source.read_bytes()
    (OUT / 'postgresql' / source.name).write_bytes(data)
    files.append({'version': source.name, 'sha256': hashlib.sha256(data).hexdigest()})
class Inspector:
    def get_table_names(self): return list(SQLModel.metadata.tables)
    def get_columns(self, table): return []
class Connection:
    dialect = type('Dialect', (), {'name': 'sqlite'})()
    statements = []
    def execute(self, statement): self.statements.append(str(statement))
connection = Connection()
@contextmanager
def transaction(bind): yield connection
runtime.inspect = lambda _: Inspector()
runtime._bind_transaction = transaction
runtime.apply_runtime_migrations(connection)
(OUT / 'runtime-additions.json').write_text(json.dumps(connection.statements, indent=2) + '\n')
(OUT / 'manifest.json').write_text(json.dumps({'postgresql': files, 'runtime_source_sha256': hashlib.sha256((ROOT / 'backend/app/schema_migrations.py').read_bytes()).hexdigest()}, indent=2) + '\n')
print('Frozen', len(files), 'versioned migrations and', len(connection.statements), 'runtime statements')
