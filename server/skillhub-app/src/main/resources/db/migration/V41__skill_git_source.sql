-- Git 源同步：源注册 + 发现项 + 来源溯源 + 同步记录
-- 全部为新增表，不修改上游既有表结构（除 skill_source_link 通过外键引用 skill）。

CREATE TABLE git_source (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    repo_url VARCHAR(512) NOT NULL,
    api_base_url VARCHAR(512),
    repo_path VARCHAR(256) NOT NULL,
    default_ref VARCHAR(128) NOT NULL DEFAULT 'main',
    subpath VARCHAR(512) NOT NULL DEFAULT '',
    namespace_slug VARCHAR(64) NOT NULL,
    auth_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    credential_ref VARCHAR(128),
    publisher_id VARCHAR(128),
    sync_interval_minutes INTEGER NOT NULL DEFAULT 60,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- false 时同步进来的版本进入待审核（安全默认）；true 才强制自动发布
    auto_publish BOOLEAN NOT NULL DEFAULT FALSE,
    default_visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_planned_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    last_sync_status VARCHAR(16),
    last_sync_message VARCHAR(2048),
    CONSTRAINT uq_git_source UNIQUE (provider, repo_url, subpath, namespace_slug)
);

CREATE INDEX idx_git_source_due
    ON git_source(enabled, last_sync_at);

-- 仓库中发现（或曾发现）的 skill 目录，tree_sha 用于跳过未变更目录。
CREATE TABLE git_source_item (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES git_source(id) ON DELETE CASCADE,
    item_path VARCHAR(512) NOT NULL,
    skill_name VARCHAR(128),
    tree_sha VARCHAR(64) NOT NULL,
    commit_sha VARCHAR(64),
    skill_id BIGINT REFERENCES skill(id) ON DELETE SET NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    last_seen_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_git_source_item UNIQUE (source_id, item_path)
);

CREATE INDEX idx_git_source_item_source
    ON git_source_item(source_id, state);

-- skill → 来源溯源：仓库/ref/commit/子路径。展示页据此显示来源徽标。
CREATE TABLE skill_source_link (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    source_id BIGINT REFERENCES git_source(id) ON DELETE SET NULL,
    provider VARCHAR(16) NOT NULL,
    repo_url VARCHAR(512) NOT NULL,
    item_path VARCHAR(512) NOT NULL,
    ref VARCHAR(128),
    commit_sha VARCHAR(64) NOT NULL,
    tree_sha VARCHAR(64) NOT NULL,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_skill_source_link UNIQUE (skill_id, source_id)
);

CREATE INDEX idx_skill_source_link_source
    ON skill_source_link(source_id, item_path);

-- 同步运行记录：手动/定时触发的可追溯历史。
CREATE TABLE git_sync_run (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES git_source(id) ON DELETE CASCADE,
    trigger_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    planned_count INTEGER NOT NULL DEFAULT 0,
    added_count INTEGER NOT NULL DEFAULT 0,
    changed_count INTEGER NOT NULL DEFAULT 0,
    unchanged_count INTEGER NOT NULL DEFAULT 0,
    removed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    message VARCHAR(2048),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_git_sync_run_source
    ON git_sync_run(source_id, started_at DESC);
