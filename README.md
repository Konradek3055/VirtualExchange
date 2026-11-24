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
**Tryb offline (domyślny):** aplikacja działa bez serwera - na telefonie chodzi lokalny silnik giełdy i boty
(market makerzy + traderzy). Boty składają zwykłe zlecenia przez ten sam silnik co gracz, więc ceny i świece
powstają wyłącznie z dopasowanych zleceń. Przełącznik **OFFLINE / ONLINE** jest w zakładce Więcej.
Offline: stan nie jest zapisywany po zamknięciu aplikacji (przycisk „Resetuj symulację" zaczyna od nowa).
Otwórz folder `android/` w Android Studio (Jellyfish lub nowsze), poczekaj na synchronizację Gradle i uruchom
konfigurację `app`.

Adres serwera i klucz API ustawiasz w aplikacji: **Więcej -> Konto i serwer** (zapisują się na telefonie).
Domyślnie: emulator `http://10.0.2.2:8000/`, telefon: IP komputera w tej samej sieci Wi-Fi, np. `http://192.168.1.20:8000/`.
Tam samo założysz nowe konto (10 000 wirtualnych USD) albo wybierzesz konto demo.

Na serwerze trzeba dopuścić połączenia z sieci lokalnej. Android ma włączony cleartext HTTP
wyłącznie dla łatwego testowania lokalnego.

## Testy
`cd backend && python -m pytest -q` (szczegóły: `backend/TESTING.md`). CI uruchamia je razem z buildem APK.

## Zakres tej wersji
- trwałe dane w SQLite (konta, salda, aktywa, zlecenia, transakcje),
- zlecenia LIMIT i MARKET, priorytet ceny/czasu,
- blokowanie środków dla otwartych zleceń,
- anulowanie zleceń,
- REST API i WebSocket z aktualizacjami rynku,
- aplikacja Compose (ciemny motyw w stylu terminala): Rynek, Handel (wykres świec OHLC z wolumenem, order book
  z paskami głębokości, formularz LIMIT/MARKET, historia transakcji), Portfel (wartość wg ostatnich cen),
  Zlecenia (anulowanie), Więcej (konto, serwer, nowe aktywo); odświeżanie przez WebSocket + zapasowo co 5 s,
- tworzenie własnych aktywów (unikalny ticker, stała podaż, twórca nie ustawia ceny, opłata emisji 100 USD i limit 3 aktywów na gracza),
- świece 1M/5M/1H, panel informacji o aktywie, zgłaszanie podejrzanych aktywów (flaga po 3 zgłoszeniach),
- przyciski 25/50/75/100% w formularzu zlecenia.

Pełna lista wymagań i statusów: `docs/CHECKLISTA.md`.

## Znane ograniczenia
- Konto demo (`demo-token`) i aktywa startowe mają saldo bez pokrycia w `total_supply` - tylko do testów lokalnych.
- Brak Gradle Wrappera (CI używa Gradle 8.9). Brak: logo aktywów, panelu moderatora, firm i akcji, PostgreSQL.
- Tryb offline: logikę silnika i botów skompilowano i uruchomiono poza Androidem (kontrola niezmienników), ale bez zapisu stanu na dysku.
- Kod Androida nie był kompilowany lokalnie (tylko kontrola składni) - pierwszą pełną weryfikacją jest GitHub Actions.
- Klucze API w bazie jawnym tekstem, brak limitów zapytań - do dopracowania przed publicznym wdrożeniem.

To wersja bazowa do dalszego rozwijania, a nie gotowa giełda produkcyjna.
Nie zawiera prawdziwych pieniędzy, płatności ani integracji z brokerem.

## WARNING

THIS APP IS IN WORK AND AGTER IT FINISH ITS GONNA GO PRIVATE. GITHUB IS USED ONLY FOR WORKFLOWS AND BETTER AI INTEGRATION FOR SEARCHING FOR BUGS AND ERRORS.
