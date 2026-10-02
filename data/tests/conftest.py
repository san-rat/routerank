import os
import re
from pathlib import Path

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

MIGRATIONS = Path(__file__).parents[2] / "backend" / "src" / "main" / "resources" / "db" / "migration"
FIXTURE = Path(__file__).parents[1] / "fixtures" / "colombo.osm.pbf"


def migration_files() -> list[Path]:
    """Flyway versioned migrations (V<n>__name.sql) in version order."""
    files = [p for p in MIGRATIONS.glob("V*__*.sql")]
    return sorted(files, key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))


@pytest.fixture(scope="session")
def database_url():
    # Same image as infra/docker-compose.yml. Migrations are applied in Flyway's
    # order; the backend's own Testcontainers test runs them through Flyway itself.
    with PostgresContainer("postgis/postgis:16-3.5", driver=None) as pg:
        url = pg.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            for path in migration_files():
                conn.execute(path.read_text())
        os.environ["DATABASE_URL"] = url
        yield url
