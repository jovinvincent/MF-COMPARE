-- PostgreSQL Schema for Mutual Fund Performance Comparison Backend
-- Strictly stores Direct Growth schemes, eligible market indices, and deduplicated historical NAVs.

CREATE TABLE IF NOT EXISTS assets (
    scheme_code VARCHAR(32) PRIMARY KEY,
    scheme_name TEXT NOT NULL,
    amc_name TEXT NOT NULL,
    category TEXT NOT NULL,
    isin VARCHAR(24),
    dedup_key TEXT NOT NULL,
    plan_type VARCHAR(16) NOT NULL DEFAULT 'Direct',
    option_type VARCHAR(16) NOT NULL DEFAULT 'Growth',
    is_index BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    inception_date DATE,
    latest_nav NUMERIC(18, 6),
    latest_nav_date DATE,
    data_source VARCHAR(32) NOT NULL,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_assets_scheme_name_trgm ON assets (LOWER(scheme_name));
CREATE INDEX IF NOT EXISTS idx_assets_is_index ON assets (is_index, is_active);

CREATE TABLE IF NOT EXISTS nav_history (
    scheme_code VARCHAR(32) NOT NULL REFERENCES assets(scheme_code) ON DELETE CASCADE,
    nav_date DATE NOT NULL,
    nav_value NUMERIC(18, 6) NOT NULL CHECK (nav_value > 0),
    data_source VARCHAR(32) NOT NULL,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scheme_code, nav_date)
);

CREATE INDEX IF NOT EXISTS idx_nav_history_date ON nav_history (nav_date DESC);

CREATE TABLE IF NOT EXISTS sync_runs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL,
    records_inserted INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);
