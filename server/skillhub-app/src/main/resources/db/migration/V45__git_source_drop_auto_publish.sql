-- Imported versions always go through review: skipping it is not a source
-- setting any more, so the column would only be a misleading leftover.
ALTER TABLE git_source DROP COLUMN IF EXISTS auto_publish;
