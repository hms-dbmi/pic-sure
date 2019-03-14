#
# Create a role, which is a collection of privileges
#
START TRANSACTION;

SET @uuidRole = REPLACE(uuid(),'-','');

# This is an example, where the SUPERUSER role is added.
INSERT INTO `role` (
	`uuid`,
	`name`,
	`description`
)
VALUES (
	unhex(@uuidRole), 
	'SUPERUSER', 
	'Superuser for PSAMA services.'
);

# This is an example, where the role is associated with the 'SYSTEM' privilege
INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `SYSTEM`)
);

COMMIT;
