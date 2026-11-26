# Mutual Fund NAV Retrieval Procedure for ISIN `INF966L01EJ3`

**Last Updated:** 2026-11-24  
**Source Verified Against:** `https://api.mfapi.in/mf/154658` (public MFAPI REST endpoint)

---

## 1. Verified Scheme Details

| Field | Value |
| :--- | :--- |
| **ISIN** | `INF966L01EJ3` |
| **Fund House** | Quant Mutual Fund |
| **Scheme Name** | Quant Income Plus Arbitrage Active Fund of Funds (FOF) |
| **Plan** | Direct Plan |
| **Option** | Growth Option |
| **Scheme Category** | Fund of Funds (Domestic) |
| **AMFI Scheme Code** | 154658 |
| **Latest NAV** | 10.13320 (as of 23-11-2026) |

---

## 2. Step-by-Step Mutual Fund NAV Retrieval Mechanism

### Step 1: ISIN to Scheme Code Resolution
- **Endpoint:** `https://api.mfapi.in/mf/<scheme_code>`
- **Method:** `GET`
- **Request:**
  ```http
  GET https://api.mfapi.in/mf/154658 HTTP/1.1
  User-Agent: Mozilla/5.0
  Accept: application/json
  ```

### Step 2: Retrieve Latest & Historical NAV
- **Response Payload:**
  ```json
  {
    "meta": {
      "fund_house": "quant Mutual Fund",
      "scheme_type": "Open Ended Schemes",
      "scheme_category": "Fund of Funds Scheme (Domestic) - Fund of Funds Scheme (Domestic)",
      "scheme_code": 154658,
      "scheme_name": "quant Income Plus Arbitrage Active FOF - Direct Plan - Growth Option",
      "isin_growth": "INF966L01EJ3",
      "isin_div_reinvestment": null
    },
    "data": [
      { "date": "23-11-2026", "nav": "10.13320" },
      { "date": "20-11-2026", "nav": "10.13450" },
      { "date": "19-11-2026", "nav": "10.12730" }
    ]
  }
  ```

### Step 3: Data Normalization & Storage
- Store `scheme_code` as primary key in `assets` table.
- Historical entries appended to `mutual_fund_nav_history` with composite key `(asset_id, date)`.
- Latest NAV cached in Redis with 24-hour TTL.

---

## 3. Complete Storage Procedure (No Duplicate Insertions)

### Database Table Definitions
```sql
CREATE TABLE assets (
    id UUID PRIMARY KEY,
    scheme_code INTEGER UNIQUE NOT NULL,
    isin VARCHAR(20),
    fund_house VARCHAR(255),
    scheme_name VARCHAR(500),
    scheme_category VARCHAR(255),
    plan_type VARCHAR(50),
    option_type VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE mutual_fund_nav_history (
    asset_id UUID REFERENCES assets(id),
    nav_date DATE NOT NULL,
    nav_value DECIMAL(12,4) NOT NULL,
    PRIMARY KEY (asset_id, nav_date)
);
```

### Deduplication Logic (SQL)
```sql
INSERT INTO mutual_fund_nav_history (asset_id, nav_date, nav_value)
VALUES (%s, %s, %s)
ON CONFLICT (asset_id, nav_date) DO NOTHING;
```

### Processing Workflow (Python)
```python
def process_and_store(scheme_code: str, nav_data: dict):
    asset_id = get_or_create_asset_id(scheme_code, nav_data['meta'])
    for entry in nav_data['data']:
        nav_date = datetime.strptime(entry['date'], '%d-%m-%Y').date()
        nav_value = Decimal(entry['nav'])
        upsert_nav_record(asset_id, nav_date, nav_value)
    cache_latest_nav(asset_id, nav_data['data'][0])

def upsert_nav_record(asset_id: UUID, nav_date: date, nav_value: Decimal):
    # Use UPSERT to prevent duplicates
    cursor.execute("""
        INSERT INTO mutual_fund_nav_history (asset_id, nav_date, nav_value)
        VALUES (%s, %s, %s)
        ON CONFLICT (asset_id, nav_date) DO UPDATE SET
            nav_value = EXCLUDED.nav_value
    """, (asset_id, nav_date, nav_value))
```

---

## 4. Batch Synchronization Schedule

| Frequency | Action | Details |
| :--- | :--- | :--- |
| **Daily 11:00 PM IST** | Full NAV Refresh | Download latest `NAVAll.txt` from AMFI |
| **Every 15 minutes (Market Hours)** | Live Price Check | For active portfolios |
| **On-Demand** | User Search | Query `api.mfapi.in/mf/<code>` directly |
