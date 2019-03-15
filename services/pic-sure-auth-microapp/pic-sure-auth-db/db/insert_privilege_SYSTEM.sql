#
# Create a connection definition in the database
# of Google type. This example requires the Email
# field in the authentication definition to exist
#
START TRANSACTION;

SET @uuidPrivilege = REPLACE(uuid(),'-','');

INSERT INTO `privilege` (
	`uuid`,
	`name`,
	`description`,
	`application_id`
)
VALUES (
	unhex(@uuidPrivilege),
	'SYSTEM',
	'Special privilege, to allow all access to all functions of PSAMA',
	NULL
);

COMMIT;
