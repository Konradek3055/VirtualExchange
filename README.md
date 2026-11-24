# VirtualExchange

Multiplayerowa giełda demonstracyjna z wirtualnymi środkami.

## Architektura
- `backend/` — Python 3.11+, FastAPI, SQLite, REST + WebSocket.
- `android/` — natywna aplikacja Android: Kotlin + Jetpack Compose.
- Backend jest osobnym serwerem. Android komunikuje się z nim przez HTTP/WebSocket.

## Uruchomienie backendu
```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS/Termux: source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Dokumentacja API: `http://127.0.0.1:8000/docs`

Domyślny tryb demonstracyjny tworzy przykładowe aktywa i konto testowe.
Klucz demo: `demo-token`. To wyłącznie lokalny klucz developerski — przed wdrożeniem
należy skonfigurować własne konta, hasła i bezpieczne tokeny.

## Android
Otwórz folder `android/` w Android Studio (Jellyfish lub nowsze), poczekaj na synchronizację Gradle i uruchom
konfigurację `app`.

Domyślny adres API:
- emulator Android: `http://10.0.2.2:8000/`
- telefon: zmień `BASE_URL` w `android/app/src/main/java/pl/virtualexchange/data/Api.kt`
  na adres IP komputera w tej samej sieci Wi-Fi.

Na serwerze trzeba dopuścić połączenia z sieci lokalnej. Android ma włączony cleartext HTTP
wyłącznie dla łatwego testowania lokalnego.

## Zakres tej wersji
- trwałe dane w SQLite (konta, salda, aktywa, zlecenia, transakcje),
- zlecenia LIMIT i MARKET, priorytet ceny/czasu,
- blokowanie środków dla otwartych zleceń,
- anulowanie zleceń,
- REST API i WebSocket z aktualizacjami rynku,
- natywna aplikacja Compose z ekranem rynku, portfelem i formularzem zlecenia.

To wersja bazowa do dalszego rozwijania, a nie gotowa giełda produkcyjna.
Nie zawiera prawdziwych pieniędzy, płatności ani integracji z brokerem.
