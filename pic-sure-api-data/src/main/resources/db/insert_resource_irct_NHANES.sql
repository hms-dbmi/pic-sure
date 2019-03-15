START TRANSACTION;

DELETE FROM `resource` WHERE `name` = 'irct-resource';

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
	'http://localhost/irct/resources',
	'/i2b2-nhanes/Demo/Demo',
	'Basic IRCT resource, for NHANES data',
	'irct-resource',
	NULL
);

COMMIT;