USE `picsure`;

SET @dictUUID = REPLACE(uuid(),'-','');


INSERT INTO `resource` (uuid, resourceRSPath, description, name, hidden) VALUES
(unhex(@dictUUID), 'http://dictionary-api/', 'frank', 'dictionary-api', 1);