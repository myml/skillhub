-- Syncing reads repositories through git itself (clone/fetch of a bare mirror),
-- so a git source no longer needs a provider API endpoint. The repository URL is
-- the only location information required.
ALTER TABLE git_source DROP COLUMN IF EXISTS api_base_url;
