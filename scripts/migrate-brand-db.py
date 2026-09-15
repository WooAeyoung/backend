"""Copy existing local SQLite databases to the WooAeyoung paths without data loss.

Old files are retained as rollback copies. Stop application servers before running.
An existing destination is never overwritten.
"""
import argparse
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SOURCES = (
    'backend/db/petbalance.db',
    'backend-spring/db/petbalance-spring.db',
    'petbalance-spring.db',
    'db/petbalance.db',
    '.runtime/share/petbalance.db',
)


def migrate(source: Path, destination: Path) -> dict[str, int]:
    if destination.exists():
        raise FileExistsError(f'Destination already exists: {destination}')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(source.as_uri() + '?mode=ro', uri=True) as old:
        with sqlite3.connect(destination) as new:
            old.backup(new)
            if new.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError(f'Database integrity check failed: {destination}')
            tables = [r[0] for r in new.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )]
            return {table: new.execute('SELECT count(*) FROM "' + table.replace('"', '""') + '"').fetchone()[0]
                    for table in tables}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--destination', type=Path)
    args = parser.parse_args()
    if bool(args.source) != bool(args.destination):
        parser.error('--source and --destination must be specified together')
    if args.source:
        counts = migrate(args.source.resolve(strict=True), args.destination.resolve())
        print(f'OK: {len(counts)} tables, {sum(counts.values())} rows; original retained')
        raise SystemExit(0)
    for relative in SOURCES:
        source = ROOT / relative
        if not source.exists():
            continue
        destination = source.with_name(source.name.replace('petbalance', 'wooaeyoung'))
        if destination.exists():
            print(f'SKIP existing: {destination.relative_to(ROOT)}')
            continue
        counts = migrate(source, destination)
        print(f'OK {destination.relative_to(ROOT)}: {len(counts)} tables, {sum(counts.values())} rows; original retained')
