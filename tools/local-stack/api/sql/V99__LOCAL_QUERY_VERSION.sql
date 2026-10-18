USE `picsure`;

-- The operations-service's Query entity maps a `version` column ("3" for the v3 ingress) that V1..V8 of
-- services/pic-sure-operations-service/db/sql do not create (they stop at the 2017-era schema plus V2..V8), so every save of a query
-- fails with "Unknown column 'version'" and the query-service answers 502. Added here for the local stack only, and only when it is
-- not there yet (a later upstream migration may add it).
SET @has_version = (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'picsure' AND table_name = 'query' AND column_name = 'version');
SET @ddl = IF(@has_version = 0, 'ALTER TABLE `query` ADD COLUMN `version` varchar(255) DEFAULT NULL', 'SELECT 1');
PREPARE ddl FROM @ddl;
EXECUTE ddl;
DEALLOCATE PREPARE ddl;
