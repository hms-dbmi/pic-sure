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
	'PIC-SURE Top Admin',
	'PIC-SURE Auth Micro App Top admin including Admin and super Admin'
);

# This is an example, where the role is associated with the 'SUPER_ADMIN' privilege
INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole),
	(SELECT uuid FROM `privilege` WHERE `name` = 'SUPER_ADMIN')
);

INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole),
	(SELECT uuid FROM `privilege` WHERE `name` = 'ADMIN')
);

COMMIT;
