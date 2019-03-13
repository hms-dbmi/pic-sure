
START TRANSACTION;

INSERT INTO `userMetadataMapping` (
	`uuid`,
	`auth0MetadataJsonPath`,
	`connectionId`,
	`generalMetadataJsonPath`
)
VALUES (
	(SELECT uuid FROM user WHERE email = '__SUPERUSER_GMAIL__'), 
	'$.email', 
	(SELECT uuid FROM connection WHERE label = 'Google'), 
	'$.email'
);

COMMIT;

