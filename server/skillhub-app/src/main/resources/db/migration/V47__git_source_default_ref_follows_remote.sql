-- An empty default_ref now means "follow the repository's default branch": the
-- sync asks the remote for HEAD and uses the branch it names. The previous
-- DEFAULT 'main' silently synced the main branch of repositories whose default
-- branch is called something else (or failed when there was no main at all).
--
-- Existing rows keep their value: an administrator may have picked that branch on
-- purpose, and changing it here would move a source to another branch unasked.
ALTER TABLE git_source ALTER COLUMN default_ref SET DEFAULT '';
