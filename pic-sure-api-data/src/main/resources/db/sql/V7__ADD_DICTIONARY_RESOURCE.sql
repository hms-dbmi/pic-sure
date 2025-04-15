USE `picsure`;

SET @dictUUID = REPLACE(uuid(),'-','');


INSERT INTO `resource` (uuid, resourceRSPath, description, name, hidden) VALUES
(unhex(@dictUUID), 'http://dictionary-api/', 'API for the data dictionary', 'dictionary-api', 1);