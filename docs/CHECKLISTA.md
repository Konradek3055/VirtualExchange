# Checklista wymagań VirtualExchange

Stan: audyt kodu + wyniki testów backendu (`cd backend && python -m pytest -q` -> **30 passed**).
Android nie był kompilowany lokalnie - weryfikacją jest GitHub Actions (job `android`).

| Wymaganie | Status | Uwagi |
|---|---|---|
| Android: Kotlin, Compose, Material 3, Gradle KTS | ✅ | folder `android/` (w specyfikacji: `/android-app`) |
| Backend: FastAPI, WebSocket, SQLite | ✅ | testy backendu przechodzą |
| PostgreSQL jako docelowa baza | ❌ | zaplanowane; dostęp przez `db.py`, SQL jest prosty |
| Wirtualne konto i saldo startowe | ✅ | 10 000 USD, `POST /accounts` |
| Fikcyjne aktywa BTC/ETH/NVDA/AAPL | ✅ | seed demo (online) / symulacja z botami (offline) |
| Market i Limit orders | ✅ | testy: priorytet cena->czas, partial fill, market bez płynności = odrzucone |
| Order book, historia zleceń i transakcji | ✅ | `/orderbook`, `/orders`, `/trades`, UI: Handel / Zlecenia |
| Świece OHLC + wolumen z prawdziwych transakcji | ✅ | `/candles?interval=1m|5m|1h`; brak transakcji = brak świec (test) |
| Realtime przez WebSocket | ✅ | `/ws/{symbol}` + odświeżanie zapasowe co 5 s |
| Wspólny rynek, ceny z transakcji | ✅ | tryb ONLINE; nowe aktywo startuje z ceną 0 |
| Brak fikcyjnych transakcji | ✅ | transakcja tylko z dopasowania; self-trade zablokowany (test) |
| Atomowe salda, brak wydawania ponad stan | ✅ | `BEGIN IMMEDIATE`, blokady, test niezmienników na losowym handlu |
| Walidacja po stronie serwera, klient nie ustala ceny/salda | ✅ | testy walidacji |
| Własne aktywa: unikalny ticker, stała podaż | ✅ | testy |
| Twórca nie ustala ceny | ✅ | `last_price=0` do pierwszej transakcji |
| Opłata emisji, limit 3 aktywów/gracz (anty-spam) | ✅ | `VX_LISTING_FEE`, `VX_MAX_ASSETS_PER_USER` |
| Publiczne informacje o aktywie | ✅ | `/assets/{symbol}`: twórca, podaż, posiadacze, kapitalizacja |
| Zgłaszanie oszustw / moderacja | 🟡 | zgłoszenia + flaga po 3 unikalnych; brak panelu admina i akcji moderatora |
| Logo aktywa | ❌ | zaplanowane (pole + bezpieczne ładowanie) |
| Firmy i akcje | ❌ | pomysł na później |
| GitHub Actions: JDK, SDK, testy, APK, artifact | ✅ | `.github/workflows/build.yml` |
| UI w stylu Binance/OKX/TradingView | 🟡 | ciemny terminal, świece+wolumen, księga z paskami, 1M/5M/1H, 25-100%; bez wskaźników i rysowania jak w TradingView |

## Boty
Tryb offline ma boty **regułowe** (market makerzy + traderzy z losowymi zleceniami) - to nie AI/LLM.
Składają zwykłe zlecenia przez ten sam silnik. W trybie online boty nie działają.

## Znane ryzyka
- Konto demo i aktywa startowe nie mają pokrycia w `total_supply`; cena startowa seedów to cena referencyjna, nie transakcja.
- Klucze API jawnym tekstem, brak rate limitów - do poprawy przed publicznym serwerem.
