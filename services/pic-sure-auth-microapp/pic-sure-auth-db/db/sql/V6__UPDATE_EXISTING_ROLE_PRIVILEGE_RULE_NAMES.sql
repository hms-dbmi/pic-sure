# We have migrated our naming convention away from "FENCE_" to a more generalized "MANAGED_".

-- Update roles table
UPDATE role
SET name = REPLACE(name, 'FENCE', 'MANAGED')
WHERE name LIKE '%FENCE%';

UPDATE role
SET description = REPLACE(description, 'FENCE', 'MANAGED')
WHERE description LIKE '%FENCE%';

-- Update privileges table
UPDATE privilege
SET name = REPLACE(name, 'FENCE', 'MANAGED')
WHERE name LIKE '%FENCE%';

UPDATE privilege
SET description = REPLACE(description, 'FENCE', 'MANAGED')
WHERE description LIKE '%FENCE%';

-- Update access rules table
UPDATE access_rule
SET name = REPLACE(name, 'FENCE', 'MANAGED')
WHERE name LIKE '%FENCE%';

UPDATE access_rule
SET description = REPLACE(description, 'FENCE', 'MANAGED')
WHERE description LIKE '%FENCE%';