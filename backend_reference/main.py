"""
FastAPI Backend Service for Mutual Fund Performance Comparison.
Provides all endpoints for fund search, metadata, NAV history, normalized comparison,
period rankings (Top 20 / Worst 20), and market indices.
"""

from datetime import date, timedelta
import os
import re
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Query
import psycopg
from psycopg.rows import dict_row

DATABASE_URL = os.environ["DATABASE_URL"]

app = FastAPI(title="MF Compare API", version="1.0.0")


def get_conn():
    return psycopg.connect(DATABASE_URL, row_factory=dict_row)


def normalize_query(q: str) -> List[str]:
    cleaned = re.sub(r"[^a-z0-9\s]", " ", q.lower().replace("&", " and "))
    return [t for t in cleaned.split() if t]


@app.get("/api/funds/search")
def search_funds(q: str = Query(""), limit: int = Query(50, ge=1, le=200)):
    tokens = normalize_query(q)
    with get_conn() as conn, conn.cursor() as cur:
        if not tokens:
            cur.execute(
                """
                SELECT scheme_code, scheme_name, amc_name, category, is_index,
                       latest_nav, latest_nav_date
                FROM assets
                WHERE is_active = TRUE
                ORDER BY is_index DESC, latest_nav_date DESC NULLS LAST, scheme_name ASC
                LIMIT %s
                """,
                (limit,),
            )
            return {"results": cur.fetchall()}

        like_clauses = " AND ".join(["LOWER(scheme_name || ' ' || amc_name) LIKE %s" for _ in tokens])
        params = [f"%{t}%" for t in tokens] + [limit]
        cur.execute(
            f"""
            SELECT scheme_code, scheme_name, amc_name, category, is_index,
                   latest_nav, latest_nav_date
            FROM assets
            WHERE is_active = TRUE AND ({like_clauses})
            ORDER BY is_index DESC, LENGTH(scheme_name) ASC, scheme_name ASC
            LIMIT %s
            """,
            params,
        )
        return {"results": cur.fetchall()}


@app.get("/api/funds")
def list_funds(only_indices: bool = False, limit: int = Query(200, ge=1, le=3000)):
    with get_conn() as conn, conn.cursor() as cur:
        if only_indices:
            cur.execute("SELECT * FROM assets WHERE is_active = TRUE AND is_index = TRUE ORDER BY scheme_name LIMIT %s", (limit,))
        else:
            cur.execute("SELECT * FROM assets WHERE is_active = TRUE ORDER BY is_index DESC, scheme_name LIMIT %s", (limit,))
        return {"funds": cur.fetchall()}


@app.get("/api/funds/{scheme_code}")
def get_fund(scheme_code: str):
    with get_conn() as conn, conn.cursor() as cur:
        cur.execute("SELECT * FROM assets WHERE scheme_code = %s", (scheme_code,))
        row = cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Scheme not found")
        return row


@app.get("/api/funds/{scheme_code}/nav")
def get_fund_nav(
    scheme_code: str,
    from_date: Optional[date] = None,
    to_date: Optional[date] = None,
):
    with get_conn() as conn, conn.cursor() as cur:
        clauses = ["scheme_code = %s"]
        params: list = [scheme_code]
        if from_date:
            clauses.append("nav_date >= %s")
            params.append(from_date)
        if to_date:
            clauses.append("nav_date <= %s")
            params.append(to_date)
        cur.execute(
            f"SELECT nav_date, nav_value::float8 AS nav FROM nav_history WHERE {' AND '.join(clauses)} ORDER BY nav_date ASC",
            params,
        )
        return {"scheme_code": scheme_code, "data": cur.fetchall()}


@app.get("/api/funds/{scheme_code}/performance")
def get_fund_performance(scheme_code: str, days: int = Query(30, ge=1, le=3650)):
    with get_conn() as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT nav_date, nav_value::float8 AS nav FROM nav_history WHERE scheme_code = %s ORDER BY nav_date DESC LIMIT 1",
            (scheme_code,),
        )
        latest = cur.fetchone()
        if not latest:
            raise HTTPException(status_code=404, detail="No NAV history")
        target = latest["nav_date"] - timedelta(days=days)
        cur.execute(
            """
            SELECT nav_date, nav_value::float8 AS nav
            FROM nav_history
            WHERE scheme_code = %s AND nav_date <= %s AND nav_date >= %s
            ORDER BY nav_date DESC LIMIT 1
            """,
            (scheme_code, target, target - timedelta(days=7)),
        )
        base = cur.fetchone()
        if not base or base["nav"] <= 0:
            raise HTTPException(status_code=422, detail="Insufficient historical data for period")
        ret = round(((latest["nav"] / base["nav"]) - 1.0) * 100.0, 2)
        return {
            "scheme_code": scheme_code,
            "days": days,
            "start_date": base["nav_date"],
            "end_date": latest["nav_date"],
            "return_percent": ret,
        }


@app.get("/api/compare")
def compare_schemes(
    codes: str = Query(..., description="Comma-separated scheme codes"),
    from_date: Optional[date] = None,
    to_date: Optional[date] = None,
):
    code_list = [c.strip() for c in codes.split(",") if c.strip()]
    if not code_list:
        raise HTTPException(status_code=400, detail="Provide at least one scheme code")
    series = []
    with get_conn() as conn, conn.cursor() as cur:
        for code in code_list:
            cur.execute("SELECT scheme_code, scheme_name, is_index FROM assets WHERE scheme_code = %s", (code,))
            meta = cur.fetchone()
            if not meta:
                continue
            clauses = ["scheme_code = %s"]
            params: list = [code]
            if from_date:
                clauses.append("nav_date >= %s")
                params.append(from_date)
            if to_date:
                clauses.append("nav_date <= %s")
                params.append(to_date)
            cur.execute(
                f"SELECT nav_date, nav_value::float8 AS nav FROM nav_history WHERE {' AND '.join(clauses)} ORDER BY nav_date ASC",
                params,
            )
            rows = cur.fetchall()
            if not rows:
                continue
            start_nav = rows[0]["nav"]
            if start_nav <= 0:
                continue
            points = [
                {"date": r["nav_date"].isoformat(), "return_percent": round(((r["nav"] / start_nav) - 1.0) * 100.0, 4)}
                for r in rows
            ]
            series.append(
                {
                    "scheme_code": code,
                    "scheme_name": meta["scheme_name"],
                    "is_index": meta["is_index"],
                    "actual_start_date": rows[0]["nav_date"].isoformat(),
                    "actual_end_date": rows[-1]["nav_date"].isoformat(),
                    "return_percent": round(((rows[-1]["nav"] / start_nav) - 1.0) * 100.0, 2),
                    "points": points,
                }
            )
    return {"series": series}


@app.get("/api/rankings")
def get_rankings(days: int = Query(7, ge=1, le=3650), limit: int = Query(20, ge=1, le=50)):
    with get_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            WITH latest_ref AS (
                SELECT MAX(latest_nav_date) AS max_d FROM assets WHERE is_index = FALSE AND is_active = TRUE
            ),
            scheme_latest AS (
                SELECT DISTINCT ON (nh.scheme_code) nh.scheme_code, nh.nav_date AS end_date, nh.nav_value::float8 AS end_nav
                FROM nav_history nh
                JOIN assets a ON a.scheme_code = nh.scheme_code
                CROSS JOIN latest_ref lr
                WHERE a.is_index = FALSE AND a.is_active = TRUE
                  AND nh.nav_date >= lr.max_d - INTERVAL '7 days'
                ORDER BY nh.scheme_code, nh.nav_date DESC
            ),
            scheme_base AS (
                SELECT DISTINCT ON (sl.scheme_code) sl.scheme_code, nh.nav_date AS start_date, nh.nav_value::float8 AS start_nav
                FROM scheme_latest sl
                JOIN nav_history nh ON nh.scheme_code = sl.scheme_code
                WHERE nh.nav_date <= sl.end_date - (%s * INTERVAL '1 day')
                  AND nh.nav_date >= sl.end_date - ((%s + 7) * INTERVAL '1 day')
                ORDER BY sl.scheme_code, nh.nav_date DESC
            ),
            computed AS (
                SELECT a.scheme_code, a.scheme_name, a.amc_name,
                       sb.start_date, sl.end_date,
                       ROUND((((sl.end_nav / sb.start_nav) - 1.0) * 100.0)::numeric, 2)::float8 AS return_percent
                FROM scheme_latest sl
                JOIN scheme_base sb ON sb.scheme_code = sl.scheme_code
                JOIN assets a ON a.scheme_code = sl.scheme_code
                WHERE sb.start_nav > 0 AND sl.end_date > sb.start_date
            )
            SELECT * FROM computed
            """,
            (days, days),
        )
        rows = cur.fetchall()
        top = sorted(rows, key=lambda r: r["return_percent"], reverse=True)[:limit]
        worst = sorted(rows, key=lambda r: r["return_percent"])[:limit]
        return {"days": days, "evaluated_count": len(rows), "top": top, "worst": worst}


@app.get("/api/indices")
def list_indices():
    with get_conn() as conn, conn.cursor() as cur:
        cur.execute("SELECT * FROM assets WHERE is_index = TRUE AND is_active = TRUE ORDER BY scheme_name")
        return {"indices": cur.fetchall()}


@app.get("/api/indices/{index_id}/history")
def get_index_history(index_id: str, from_date: Optional[date] = None, to_date: Optional[date] = None):
    return get_fund_nav(scheme_code=index_id, from_date=from_date, to_date=to_date)
