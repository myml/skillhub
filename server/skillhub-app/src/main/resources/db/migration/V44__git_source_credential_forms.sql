-- The credential is now the value typed on the Git source page: "<user>:<deploy
-- token>" for HTTP(S) remotes, or the private key itself for SSH remotes. It is
-- therefore stored as free text called "credential".
ALTER TABLE git_source RENAME COLUMN credential_ref TO credential;
ALTER TABLE git_source ALTER COLUMN credential TYPE TEXT;

-- Values written before this change were names of secrets held in server
-- configuration, a model that no longer exists; keep the sources but drop the
-- stale names so they read as public repositories until a credential is entered.
UPDATE git_source SET credential = NULL WHERE credential IS NOT NULL;
