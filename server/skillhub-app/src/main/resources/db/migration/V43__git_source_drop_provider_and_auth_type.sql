-- The platform is neither configured nor stored any more: every server is read
-- through git itself, and commit links are not assumed to follow any layout.
-- Authentication is now expressed by the (optional) credential reference alone.
ALTER TABLE git_source DROP COLUMN IF EXISTS provider;
ALTER TABLE git_source DROP COLUMN IF EXISTS auth_type;
ALTER TABLE skill_source_link DROP COLUMN IF EXISTS provider;

-- The same repository may no longer be registered twice for one subpath and namespace.
ALTER TABLE git_source DROP CONSTRAINT IF EXISTS uq_git_source;
ALTER TABLE git_source ADD CONSTRAINT uq_git_source UNIQUE (repo_url, subpath, namespace_slug);
