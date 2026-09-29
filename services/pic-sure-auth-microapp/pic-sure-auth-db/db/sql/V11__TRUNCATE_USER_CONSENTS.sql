-- The `consents` column changed shape from a map of consent groups
-- ({"_consents":[...],"_topmed_consents":[...]}) to a flat set of consent
-- strings (["phs1234.c1", ...]). Rows written by the previous format can no
-- longer be deserialized, and UserService.updateUserConsents reads the existing
-- row before overwriting it, so a stale row would fail login rather than being
-- replaced.
--
-- The table is a derived cache: every login rebuilds a user's consents from
-- their passport via updateUserConsents, so emptying it loses nothing.
TRUNCATE TABLE user_consents;
