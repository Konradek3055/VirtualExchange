import sqlite3
from pathlib import Path
from contextlib import contextmanager

DB_PATH = Path(__file__).resolve().parent.parent / "virtualexchange.sqlite3"

def connect():
    con = sqlite3.connect(DB_PATH, check_same_thread=False)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    return con

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
            PRIMARY KEY(account_id, symbol)
        );
        CREATE TABLE IF NOT EXISTS assets(
            symbol TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            last_price TEXT NOT NULL DEFAULT '0'
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
        """)
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
