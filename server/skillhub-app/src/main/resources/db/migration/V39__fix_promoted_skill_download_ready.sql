-- Fix dirty download_ready for skills promoted to the global namespace before
-- commit 2af0bf18 ("fix(promotion): copy bundleReady and downloadReady when
-- promoting skill to global") was deployed.
--
-- The old approvePromotion flow created the target SkillVersion without copying
-- bundleReady/downloadReady from the source version, so promoted versions were
-- left with download_ready = FALSE (column default). The web download button is
-- disabled whenever downloadAvailable -> isDownloadReady() is false, so these
-- promoted skills could not be downloaded from the web UI.
--
-- Downloads of such versions do not need a prebuilt bundle: SkillDownloadService
-- falls back to building the zip on the fly from the copied skill_file records
-- (which reuse the source files' storage keys), so marking the rows download_ready
-- is sufficient. bundle_ready is intentionally left FALSE because no bundle was
-- ever uploaded for these versions.
--
-- Idempotent: the WHERE clause only matches rows still marked FALSE.

UPDATE skill_version sv
SET download_ready = TRUE
WHERE sv.status = 'PUBLISHED'
  AND sv.file_count > 0
  AND sv.download_ready = FALSE
  AND sv.skill_id IN (
      SELECT s.id
      FROM skill s
      WHERE s.source_skill_id IS NOT NULL
  );
