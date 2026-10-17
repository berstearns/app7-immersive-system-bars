-- Turso (libSQL / SQLite) schema for app7 sync targets.
-- Mirror of the Python worker's archive_schema.sql, with `device_id` + `user_id`
-- promoted to columns so a row's identity is `(device_id, local_id)`.
-- `synced` is intentionally NOT present — that's a local-only concept.
--
-- Apply via:
--   turso db shell <db-name> < schemas/turso-schema.sql

CREATE TABLE IF NOT EXISTS session_events (
    device_id      TEXT    NOT NULL,
    local_id       INTEGER NOT NULL,
    event_type     TEXT    NOT NULL,
    timestamp      INTEGER NOT NULL,
    duration_ms    INTEGER,
    comic_id       TEXT,
    chapter_name   TEXT,
    page_id        TEXT,
    page_title     TEXT,
    user_id        TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS annotation_records (
    device_id            TEXT    NOT NULL,
    local_id             INTEGER NOT NULL,
    image_id             TEXT    NOT NULL,
    box_index            INTEGER NOT NULL,
    box_x                REAL    NOT NULL,
    box_y                REAL    NOT NULL,
    box_width            REAL    NOT NULL,
    box_height           REAL    NOT NULL,
    label                TEXT    NOT NULL,
    timestamp            INTEGER NOT NULL,
    tap_x                REAL    NOT NULL,
    tap_y                REAL    NOT NULL,
    region_type          TEXT    NOT NULL DEFAULT 'BUBBLE',
    parent_bubble_index  INTEGER,
    token_index          INTEGER,
    comic_id             TEXT,
    user_id              TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS chat_messages (
    device_id  TEXT    NOT NULL,
    local_id   INTEGER NOT NULL,
    sender     TEXT    NOT NULL,
    text       TEXT    NOT NULL,
    timestamp  INTEGER NOT NULL,
    user_id    TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS page_interactions (
    device_id         TEXT    NOT NULL,
    local_id          INTEGER NOT NULL,
    interaction_type  TEXT    NOT NULL,
    timestamp         INTEGER NOT NULL,
    comic_id          TEXT,
    chapter_name      TEXT,
    page_id           TEXT,
    normalized_x      REAL,
    normalized_y      REAL,
    hit_result        TEXT,
    user_id           TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS app_launch_records (
    device_id         TEXT    NOT NULL,
    local_id          INTEGER NOT NULL,
    package_name      TEXT    NOT NULL,
    timestamp         INTEGER NOT NULL,
    comic_id          TEXT,
    current_chapter   TEXT,
    current_page_id   TEXT,
    user_id           TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS settings_changes (
    device_id     TEXT    NOT NULL,
    local_id      INTEGER NOT NULL,
    setting_key   TEXT    NOT NULL,
    old_value     TEXT    NOT NULL,
    new_value     TEXT    NOT NULL,
    timestamp     INTEGER NOT NULL,
    user_id       TEXT,
    PRIMARY KEY (device_id, local_id)
);

-- region_translations is keyed by its own `id` (string PK), per the producer.
CREATE TABLE IF NOT EXISTS region_translations (
    id                    TEXT    NOT NULL PRIMARY KEY,
    image_id              TEXT    NOT NULL,
    bubble_index          INTEGER NOT NULL,
    original_text         TEXT    NOT NULL,
    meaning_translation   TEXT    NOT NULL,
    literal_translation   TEXT    NOT NULL,
    source_language       TEXT    NOT NULL DEFAULT 'ja',
    target_language       TEXT    NOT NULL DEFAULT 'en',
    device_id             TEXT,
    user_id               TEXT
);

CREATE TABLE IF NOT EXISTS app_sessions (
    device_id     TEXT    NOT NULL,
    local_id      INTEGER NOT NULL,
    start_ts      INTEGER NOT NULL,
    end_ts        INTEGER,
    duration_ms   INTEGER,
    app_version   TEXT    NOT NULL DEFAULT '',
    user_id       TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS comic_sessions (
    device_id              TEXT    NOT NULL,
    local_id               INTEGER NOT NULL,
    app_session_local_id   INTEGER NOT NULL,
    comic_id               TEXT    NOT NULL,
    start_ts               INTEGER NOT NULL,
    end_ts                 INTEGER,
    duration_ms            INTEGER,
    pages_read             INTEGER,
    user_id                TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS chapter_sessions (
    device_id                TEXT    NOT NULL,
    local_id                 INTEGER NOT NULL,
    comic_session_local_id   INTEGER NOT NULL,
    comic_id                 TEXT    NOT NULL,
    chapter_name             TEXT    NOT NULL,
    start_ts                 INTEGER NOT NULL,
    end_ts                   INTEGER,
    duration_ms              INTEGER,
    pages_visited            INTEGER,
    user_id                  TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS page_sessions (
    device_id                  TEXT    NOT NULL,
    local_id                   INTEGER NOT NULL,
    chapter_session_local_id   INTEGER NOT NULL,
    comic_id                   TEXT    NOT NULL,
    page_id                    TEXT    NOT NULL,
    enter_ts                   INTEGER NOT NULL,
    leave_ts                   INTEGER,
    dwell_ms                   INTEGER,
    interactions_n             INTEGER NOT NULL DEFAULT 0,
    user_id                    TEXT,
    PRIMARY KEY (device_id, local_id)
);

-- Added 2026-05-02: per-user, per-page bubble reorder ("edit mode" feature).
-- original_indices is a JSON-encoded List<Int> string because the Turso
-- adapter falls back to JSON text for nested arrays via toTursoArg().
-- PK includes updated_at so re-saves accumulate as history rows that aren't
-- silently dropped by INSERT OR IGNORE; "current ordering for a page" is
-- the row with MAX(updated_at) for the (device_id, user_id, comic_id,
-- image_id) tuple.
CREATE TABLE IF NOT EXISTS page_bubble_orderings (
    device_id         TEXT    NOT NULL,
    user_id           TEXT    NOT NULL,
    comic_id          TEXT    NOT NULL,
    image_id          TEXT    NOT NULL,
    original_indices  TEXT    NOT NULL,
    updated_at        INTEGER NOT NULL,
    PRIMARY KEY (device_id, user_id, comic_id, image_id, updated_at)
);

CREATE INDEX IF NOT EXISTS page_bubble_orderings_by_page
  ON page_bubble_orderings (device_id, user_id, comic_id, image_id, updated_at DESC);
