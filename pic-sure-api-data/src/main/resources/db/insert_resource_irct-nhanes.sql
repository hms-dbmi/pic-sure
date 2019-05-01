START TRANSACTION;

DELETE FROM `resource` WHERE `name` = 'irct-nhanes';

SET @uuidResource = REPLACE(uuid(),'-','');

INSERT INTO `resource` (
  `uuid`,
  `targetURL`,
  `resourceRSPath`,
  `description`,
  `name`,
  `token`
) VALUES (
	unhex(@uuidResource),
	NULL,
	'/i2b2-nhanes/Demo/Demo',
	'Basic IRCT resource, for NHANES data',
	'irct-nhanes',
	NULL
);

COMMIT;