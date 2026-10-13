-- 下载统计：明细事件表 + 日聚合表
-- 明细用于精确维度分析（渠道/版本/来源），按保留期清理；
-- 日聚合用于长期保留与快速查询，由定时任务幂等重算。

CREATE TABLE skill_download_event (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    version_id BIGINT,
    channel VARCHAR(16) NOT NULL DEFAULT 'unknown',
    actor_id VARCHAR(128),
    client_hash VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_skill_download_event_skill_time
    ON skill_download_event(skill_id, occurred_at DESC);

CREATE INDEX idx_skill_download_event_time
    ON skill_download_event(occurred_at);

CREATE TABLE skill_download_daily (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    version_id BIGINT NOT NULL DEFAULT 0,
    channel VARCHAR(16) NOT NULL DEFAULT 'unknown',
    day DATE NOT NULL,
    download_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_skill_download_daily UNIQUE (skill_id, version_id, channel, day)
);

CREATE INDEX idx_skill_download_daily_day
    ON skill_download_daily(day);

CREATE INDEX idx_skill_download_daily_skill_day
    ON skill_download_daily(skill_id, day);
