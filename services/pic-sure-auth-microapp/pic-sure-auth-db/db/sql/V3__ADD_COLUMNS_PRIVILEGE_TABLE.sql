USE `auth`;

alter table `privilege` ADD COLUMN (queryScope VARCHAR(8192));
alter table `privilege` ADD COLUMN (queryTemplate VARCHAR(8192));
