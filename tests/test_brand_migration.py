import importlib.util
import sqlite3
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location('brand_migration', Path(__file__).resolve().parents[1] / 'scripts/migrate-brand-db.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_database_copy_preserves_rows_and_original(tmp_path):
    source, destination = tmp_path / 'old.db', tmp_path / 'new.db'
    with sqlite3.connect(source) as db:
        db.execute('CREATE TABLE pets (id INTEGER PRIMARY KEY, name TEXT)')
        db.execute('INSERT INTO pets VALUES (1, ?)', ('나비',))
    assert module.migrate(source, destination) == {'pets': 1}
    for path in (source, destination):
        with sqlite3.connect(path) as db:
            assert db.execute('SELECT * FROM pets').fetchall() == [(1, '나비')]
    with pytest.raises(FileExistsError):
        module.migrate(source, destination)
