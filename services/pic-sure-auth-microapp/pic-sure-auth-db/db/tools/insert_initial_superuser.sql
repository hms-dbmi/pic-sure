#
# This script will insert the initial superuser account
# and corresponding information into the appropriate tables.
# Assuming, that all objects (tables and views) have been
# created, and the initial privileges and roles have been
# inserted into the appropriate tables already.
#
# Just in case clearing the underlying tables is required
# One might execute the folloring scripts, but BEWARE that
# these commands will remove ALL user information from 
# the database.
#
# 
# DELETE FROM `user_role`;
# DELETE FROM `user`;
# DELETE FROM `userMetadataMapping`;
# DELETE FROM `connection`;
#
#
START TRANSACTION;

#configuration for connection table
SET @uuidConnection = REPLACE(uuid(),'-','');
SET @uuidUser = REPLACE(uuid(),'-','');

# The default first connection information is for Google
INSERT INTO `connection` (
	`uuid`,
	`label`,
	`id`,
	`subprefix`,
	`requiredFields`
) VALUES (
	@uuidConnection, 
	'Google', 
	'google-oauth2', 
	'google-oauth2|', 
	'[{\"label\":\"Email\", \"id\":\"email\"}]'
);

INSERT INTO user (
	`uuid`,
	`general_metadata`,
	`connectionId`,
	`matched`
) VALUES (
	unhex(@uuidUser), 
	"{\"email\":\"__SUPERUSER_GMAIL_ADDRESS__\"}", 
	@uuidConnection, 
	false
);

# Add the initial SYSTEM role for the user.
# Assuming, that all superuser privileges have been
# assigned to this role, already, during creation
# of the database.
INSERT INTO user_role (
	`user_id`,
	`role_id`
) VALUES (
	UNHEX(@uuidUser),
	(SELECT MIN(uuid) FROM management_view WHERE privilege_name LIKE 'SYSTEM')
);

#### insert into userMetadataMapping
INSERT INTO `userMetadataMapping` (
	`uuid`,
	`auth0MetadataJsonPath`,
	`connectionId`,
	`generalMetadataJsonPath`,
) VALUES (
	REPLACE(uuid(),'-',''), 
	'$.email', 
	@uuidConnection, 
	'$.email'
);

COMMIT;
