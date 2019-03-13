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
	'TM_ADMIN', 
	'i2b2/tranSmart administrator role.'
);

# The i2b2/tranSmart administrator role has ADMIN and PUBLIC_USER privileges
INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_ADMIN`)
);

INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_PUBLIC_USER`)
);

# The i2b2/tranSmart level1 role has STUDY_OWNER and PUBLIC_USER privileges
INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_STUDY_OWNER`)
);

INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_PUBLIC_USER`)
);

# The i2b2/tranSmart level2 role has DATASET_EXPLORER and PUBLIC_USER privileges
INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_DATASET_EXPLORER`)
);

INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_PUBLIC_USER`)
);

# The i2b2/tranSmart level0 (authenticated, but not authorized for data access) role has only PUBLIC_USER privileges
INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_PUBLIC`)
);

INSERT INTO `role_privilege` (
	`role_id`,
	`privilege_id`
)
VALUES (
	unhex(@uuidRole), 
	(SELECT uuid FROM `privilege` WHERE `name` = `TM_PUBLIC_USER`)
);

COMMIT;
