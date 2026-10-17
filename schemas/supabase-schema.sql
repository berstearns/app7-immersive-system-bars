-- Supabase (Postgres) schema for app7 sync targets.
-- Apply via the Supabase SQL editor, or `psql $SUPABASE_DB_URL < schemas/supabase-schema.sql`.
-- RLS is hardcoded to user_id = 'user9395' so a leaked anon key can only insert
-- rows for that user. Change the policy to expand to multi-user later.

CREATE TABLE IF NOT EXISTS session_events (
    device_id      TEXT    NOT NULL,
    local_id       BIGINT  NOT NULL,
    event_type     TEXT    NOT NULL,
    timestamp      BIGINT  NOT NULL,
    duration_ms    BIGINT,
    comic_id       TEXT,
    chapter_name   TEXT,
    page_id        TEXT,
    page_title     TEXT,
    user_id        TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS annotation_records (
    device_id            TEXT    NOT NULL,
    local_id             BIGINT  NOT NULL,
    image_id             TEXT    NOT NULL,
    box_index            INTEGER NOT NULL,
    box_x                DOUBLE PRECISION NOT NULL,
    box_y                DOUBLE PRECISION NOT NULL,
    box_width            DOUBLE PRECISION NOT NULL,
    box_height           DOUBLE PRECISION NOT NULL,
    label                TEXT    NOT NULL,
    timestamp            BIGINT  NOT NULL,
    tap_x                DOUBLE PRECISION NOT NULL,
    tap_y                DOUBLE PRECISION NOT NULL,
    region_type          TEXT    NOT NULL DEFAULT 'BUBBLE',
    parent_bubble_index  INTEGER,
    token_index          INTEGER,
    comic_id             TEXT,
    user_id              TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS chat_messages (
    device_id  TEXT    NOT NULL,
    local_id   BIGINT  NOT NULL,
    sender     TEXT    NOT NULL,
    text       TEXT    NOT NULL,
    timestamp  BIGINT  NOT NULL,
    user_id    TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS page_interactions (
    device_id         TEXT    NOT NULL,
    local_id          BIGINT  NOT NULL,
    interaction_type  TEXT    NOT NULL,
    timestamp         BIGINT  NOT NULL,
    comic_id          TEXT,
    chapter_name      TEXT,
    page_id           TEXT,
    normalized_x      DOUBLE PRECISION,
    normalized_y      DOUBLE PRECISION,
    hit_result        TEXT,
    user_id           TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS app_launch_records (
    device_id         TEXT    NOT NULL,
    local_id          BIGINT  NOT NULL,
    package_name      TEXT    NOT NULL,
    timestamp         BIGINT  NOT NULL,
    comic_id          TEXT,
    current_chapter   TEXT,
    current_page_id   TEXT,
    user_id           TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS settings_changes (
    device_id     TEXT    NOT NULL,
    local_id      BIGINT  NOT NULL,
    setting_key   TEXT    NOT NULL,
    old_value     TEXT    NOT NULL,
    new_value     TEXT    NOT NULL,
    timestamp     BIGINT  NOT NULL,
    user_id       TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS region_translations (
    id                    TEXT    PRIMARY KEY,
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
    local_id      BIGINT  NOT NULL,
    start_ts      BIGINT  NOT NULL,
    end_ts        BIGINT,
    duration_ms   BIGINT,
    app_version   TEXT    NOT NULL DEFAULT '',
    user_id       TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS comic_sessions (
    device_id              TEXT    NOT NULL,
    local_id               BIGINT  NOT NULL,
    app_session_local_id   BIGINT  NOT NULL,
    comic_id               TEXT    NOT NULL,
    start_ts               BIGINT  NOT NULL,
    end_ts                 BIGINT,
    duration_ms            BIGINT,
    pages_read             INTEGER,
    user_id                TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS chapter_sessions (
    device_id                TEXT    NOT NULL,
    local_id                 BIGINT  NOT NULL,
    comic_session_local_id   BIGINT  NOT NULL,
    comic_id                 TEXT    NOT NULL,
    chapter_name             TEXT    NOT NULL,
    start_ts                 BIGINT  NOT NULL,
    end_ts                   BIGINT,
    duration_ms              BIGINT,
    pages_visited            INTEGER,
    user_id                  TEXT,
    PRIMARY KEY (device_id, local_id)
);

CREATE TABLE IF NOT EXISTS page_sessions (
    device_id                  TEXT    NOT NULL,
    local_id                   BIGINT  NOT NULL,
    chapter_session_local_id   BIGINT  NOT NULL,
    comic_id                   TEXT    NOT NULL,
    page_id                    TEXT    NOT NULL,
    enter_ts                   BIGINT  NOT NULL,
    leave_ts                   BIGINT,
    dwell_ms                   BIGINT,
    interactions_n             INTEGER NOT NULL DEFAULT 0,
    user_id                    TEXT,
    PRIMARY KEY (device_id, local_id)
);

-- ── RLS ─────────────────────────────────────────────────────────
-- Enable RLS on every syncable table and allow the anon role to insert
-- rows ONLY when user_id = 'user9395'. No SELECT/UPDATE/DELETE from anon
-- (so a leaked anon key cannot read or modify existing data).
DO $$
DECLARE t TEXT;
BEGIN
    FOR t IN SELECT unnest(ARRAY[
        'session_events',
        'annotation_records',
        'chat_messages',
        'page_interactions',
        'app_launch_records',
        'settings_changes',
        'region_translations',
        'app_sessions',
        'comic_sessions',
        'chapter_sessions',
        'page_sessions'
    ])
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'DROP POLICY IF EXISTS user9395_insert ON %I',
            t
        );
        EXECUTE format(
            'CREATE POLICY user9395_insert ON %I FOR INSERT TO anon
                WITH CHECK (user_id = ''user9395'')',
            t
        );
    END LOOP;
END
$$;
