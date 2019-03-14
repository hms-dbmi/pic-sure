
START TRANSACTION;

SET @uuidConnection = REPLACE(uuid(),'-','');

INSERT INT `connection` (
	`uuid`,
	`label`,
	`id`,
	`subprefix`,
	`requiredFields`
) VALUES (
		unhex(@uuidConnection), 
		'Google', 
		'google-oauth2', 
		'google-oauth2|', 
		'[{\"label\":\"Email\", \"id\":\"email\"}]'
);

COMMIT;