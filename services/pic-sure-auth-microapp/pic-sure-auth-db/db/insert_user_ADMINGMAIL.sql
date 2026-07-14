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
SET @uuidUser = REPLACE(uuid(),'-','');

INSERT INTO user (
	`uuid`,
	`general_metadata`,
	`connectionId`,
	`matched`
) VALUES (
	unhex(@uuidUser),
	"{\"email\":\"__SUPERUSER_GMAIL_ADDRESS__\"}",
	(SELECT `uuid` FROM `connection` WHERE `label` = 'Google'),
	false
);

# Add the initial ADMIN role for the user.
# Assuming, that all superuser privileges have been
# assigned to this role, already, during creation
# of the database.
INSERT INTO user_role (
	`user_id`,
	`role_id`
) VALUES (
	UNHEX(@uuidUser),
	(SELECT `uuid` FROM `role` WHERE name = 'PIC-SURE Top Admin')
);

#### insert into userMetadataMapping
SET @uuidMetaData = REPLACE(uuid(),'-','');

INSERT INTO `userMetadataMapping` (
	`uuid`,
	`auth0MetadataJsonPath`,
	`connectionId`,
	`generalMetadataJsonPath`
) VALUES (
	unhex(@uuidMetaData),
	'$.email',
	(SELECT `uuid` FROM `connection` WHERE `label` = 'Google'),
	'$.email'
);

COMMIT;
