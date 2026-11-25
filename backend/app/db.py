
import os
import sqlite3
from pathlib import Path
from contextlib import contextmanager

DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent / "virtualexchange.sqlite3"

def db_path():
    """Ścieżka bazy; można nadpisać zmienną VE_DB_PATH (np. w testach)."""
    return Path(os.environ.get("VE_DB_PATH") or DEFAULT_DB_PATH)

def connect():
    db = sqlite3.connect(db_path(), timeout=30)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA busy_timeout=30000")
    return db

def init_db():
    with connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS accounts(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            api_key TEXT UNIQUE NOT NULL
        );
        CREATE TABLE IF NOT EXISTS balances(
            account_id INTEGER NOT NULL REFERENCES accounts(id),
            symbol TEXT NOT NULL,
            available TEXT NOT NULL DEFAULT '0',
            locked TEXT NOT NULL DEFAULT '0',
            PRIMARY KEY(account_id,symbol)
        );
        CREATE TABLE IF NOT EXISTS assets(
            symbol TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            last_price TEXT NOT NULL DEFAULT '0',
            description TEXT NOT NULL DEFAULT '',
            creator_id INTEGER REFERENCES accounts(id),
            total_supply TEXT NOT NULL DEFAULT '0',
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS orders(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id),
            symbol TEXT NOT NULL REFERENCES assets(symbol),
            side TEXT NOT NULL,
            order_type TEXT NOT NULL,
            quantity TEXT NOT NULL,
            remaining TEXT NOT NULL,
            price TEXT,
            price_i INTEGER,
            status TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS trades(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            symbol TEXT NOT NULL,
            price TEXT NOT NULL,
            quantity TEXT NOT NULL,
            buy_order_id INTEGER NOT NULL,
            sell_order_id INTEGER NOT NULL,
            buyer_id INTEGER NOT NULL,
            seller_id INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS asset_reports(
            symbol TEXT NOT NULL REFERENCES assets(symbol),
            account_id INTEGER NOT NULL REFERENCES accounts(id),
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(symbol,account_id)
        );
        CREATE TABLE IF NOT EXISTS company_development(
            symbol TEXT PRIMARY KEY REFERENCES assets(symbol) ON DELETE CASCADE,
            owner_id INTEGER NOT NULL REFERENCES accounts(id),
            office_level INTEGER NOT NULL DEFAULT 0,
            research_level INTEGER NOT NULL DEFAULT 0,
            marketing_level INTEGER NOT NULL DEFAULT 0,
            invested TEXT NOT NULL DEFAULT '0',
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        """)
        cols = {r[1] for r in db.execute("PRAGMA table_info(orders)")}
        if "price_i" not in cols:   # migracja starszych baz: cena jako liczba całkowita (cena*1e8)
            db.execute("ALTER TABLE orders ADD COLUMN price_i INTEGER")
            db.execute("UPDATE orders SET price_i=CAST(ROUND(CAST(price AS REAL)*100000000) AS INTEGER) WHERE price IS NOT NULL")
        company_cols = {r[1] for r in db.execute("PRAGMA table_info(company_development)")}
        company_migrations = {
            "production_level": "INTEGER NOT NULL DEFAULT 0",
            "logistics_level": "INTEGER NOT NULL DEFAULT 0",
            "employees": "INTEGER NOT NULL DEFAULT 3",
            "cash": "TEXT NOT NULL DEFAULT '0'",
            "revenue_total": "TEXT NOT NULL DEFAULT '0'",
            "expense_total": "TEXT NOT NULL DEFAULT '0'",
            "dividends_total": "TEXT NOT NULL DEFAULT '0'",
            "last_revenue": "TEXT NOT NULL DEFAULT '0'",
            "last_expense": "TEXT NOT NULL DEFAULT '0'",
            "reputation": "REAL NOT NULL DEFAULT 50",
            "customers": "INTEGER NOT NULL DEFAULT 0",
            "last_settlement": "INTEGER NOT NULL DEFAULT 0",
        }
        for column, declaration in company_migrations.items():
            if column not in company_cols:
                db.execute(f"ALTER TABLE company_development ADD COLUMN {column} {declaration}")
        db.execute("CREATE INDEX IF NOT EXISTS idx_orders_book ON orders(symbol,side,status,id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_orders_match ON orders(symbol,side,status,price_i,id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_trades_symbol ON trades(symbol,id)")
        db.commit()

@contextmanager
def transaction():
    db = connect()
    try:
        db.execute("BEGIN IMMEDIATE")
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()
