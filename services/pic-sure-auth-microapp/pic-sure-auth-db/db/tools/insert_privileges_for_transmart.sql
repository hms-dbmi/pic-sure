#
# Create the privileges used for i2b2/tranSmart application
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
	'TM_ADMIN',
	'Administrator privilege for i2b2/tranSmart application',
	(SELECT `uuid` FROM `application` WHERE `name` = 'TRANSMART')
);

SET @uuidPrivilege = REPLACE(uuid(),'-','');

INSERT INTO `privilege` (
	`uuid`,
	`name`,
	`description`,
	`application_id`
)
VALUES (
	unhex(@uuidPrivilege),
	'TM_STUDY_OWNER',
	'Level1 priveilege for i2b2/tranSmart user, allowing download functionality, in addition to Level1 privileges.',
	(SELECT `uuid` FROM `application` WHERE `name` = 'TRANSMART')
);

SET @uuidPrivilege = REPLACE(uuid(),'-','');

INSERT INTO `privilege` (
	`uuid`,
	`name`,
	`description`,
	`application_id`
)
VALUES (
	unhex(@uuidPrivilege),
	'TM_DATASET_EXPLORER',
	'Level2 privilege for i2b2/tranSmart user, allowing to run advanced statistics, but no download or grid view.',
	(SELECT `uuid` FROM `application` WHERE `name` = 'TRANSMART')
);

SET @uuidPrivilege = REPLACE(uuid(),'-','');

INSERT INTO `privilege` (
	`uuid`,
	`name`,
	`description`,
	`application_id`
)
VALUES (
	unhex(@uuidPrivilege),
	'TM_PUBLIC_USER',
	'Base level privilege, for i2b2/tranSmart, allowing the user to log in, and see counts.',
	(SELECT `uuid` FROM `application` WHERE `name` = 'TRANSMART')
);


COMMIT;
