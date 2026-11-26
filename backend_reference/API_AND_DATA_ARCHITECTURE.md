# MF Compare — Data & Architecture Specification

## 1. Inspected Reference Material
The attached reference procedure (`MUTUAL_FUND_RETRIEVAL_PROCEDURE.md`) and project sources documented:
- **MFAPI (`https://api.mfapi.in/mf/{schemeCode}`)**: Returns JSON `{ "meta": ..., "data": [ { "date": "dd-MM-yyyy", "nav": "..." } ] }` with full historical NAV records per scheme.
- **AMFI Daily Master & NAV Feed (`https://www.amfiindia.com/spages/NAVAll.txt`)**: Semicolon-delimited official AMFI master feed containing every active Indian mutual fund scheme, ISIN, AMC, category, latest NAV, and published date in a single lightweight download.
- **Market Indices**: Legitimate daily index closes via Yahoo Finance chart endpoints (`^NSEI` Nifty 50, `^BSESN` BSE Sensex, `^CRSLDX` Nifty 500, `^NSMIDCP`/`^CNXJR` Nifty Next 50, `NIFTYMIDCAP150.NS`, `NIFTYSMLCAP250.NS`).

## 2. Eligibility & Deduplication Rules
- **Included**: Direct Plan Growth Indian mutual funds (explicit `"direct"` and `"growth"` tokens) + market indices.
- **Excluded**: Regular plans, IDCW plans, Dividend payout/reinvestment, Bonus, Sweep, Institutional, and Retail plans.
- **Deduplication**: Canonical deduplication by `ISIN` (when valid `INF...`) or normalized base name, and `(scheme_code, date_epoch_day)` composite primary key in Room/PostgreSQL so duplicate NAV points are impossible.

## 3. Android Offline-First Architecture vs Optional Server Backend
In this Android Studio Build environment, the Android app runs completely standalone on-device using:
- **Local Room Database (`mf_compare_db`)** for zero-latency fuzzy search, favourites persistence, and deduplicated historical NAV caching.
- **On-device Sync Engine (`RemoteDataSources` + `DailyNavSyncWorker`)** syncing AMFI `NAVAll.txt`, MFAPI scheme histories incrementally, and Yahoo Finance market indices.
- **Optional PostgreSQL + FastAPI Reference (`backend_reference/`)**: Provided for server deployment with `schema.sql` and `main.py` implementing all REST endpoints (`/api/funds/search`, `/api/compare`, `/api/rankings`, `/api/indices`). Note: `api.mfapi.in` & AMFI rate-limit considerations require bounded concurrency (4–6 concurrent connections) during initial full-universe history sync; subsequent daily updates refresh the entire universe via AMFI's single `NAVAll.txt` feed.
