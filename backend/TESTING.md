# Testy backendu

```bash
cd backend
python -m pip install -r requirements.txt
python -m pytest -q
```

Każdy test dostaje świeżą bazę SQLite (zmienna `VE_DB_PATH`, patrz `tests/conftest.py`).
`tests/test_engine.py` sprawdza m.in.: priorytet cena-czas, cenę maker, zwrot przy price improvement,
market buy z ograniczeniem środków, brak podwójnego wydania, self-trade, walidację, dokładne porównanie cen
(bez float), świece tylko z realnych transakcji, WebSocket oraz losowy stres z kontrolą niezmienników
(stała podaż USD i aktywów, brak ujemnych sald, blokady == otwarte zlecenia).
Wynik na dzień utworzenia: 24 testy zaliczone. Android nie był kompilowany lokalnie - pierwszą weryfikacją jest GitHub Actions.

`tests/test_economy_core.py` (7) i `tests/test_economy_integration.py` (10): migracje, trwałość, API, ticki, rollback, zgodność ze starym systemem. Razem 52 testy.
`tests/test_economy_circulation.py` (10): 100 ticków, obieg pieniądza, księgowość, restart, rollback, operacje gracza, most do starych firm. Razem 62 testy.
