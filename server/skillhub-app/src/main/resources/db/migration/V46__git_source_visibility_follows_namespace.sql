-- Visibility is a consequence of the namespace, not a per-source setting: a source
-- in the global namespace imports public skills, a source in a team namespace
-- imports skills its members can see. Align the rows written before this rule.
UPDATE git_source s
SET default_visibility = CASE WHEN n.type = 'GLOBAL' THEN 'PUBLIC' ELSE 'NAMESPACE_ONLY' END
FROM namespace n
WHERE n.slug = s.namespace_slug
  AND s.default_visibility IS DISTINCT FROM
      (CASE WHEN n.type = 'GLOBAL' THEN 'PUBLIC' ELSE 'NAMESPACE_ONLY' END);
