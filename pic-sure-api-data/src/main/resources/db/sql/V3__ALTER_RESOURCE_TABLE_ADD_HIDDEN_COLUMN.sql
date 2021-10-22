USE `picsure`;

alter table `resource` add column 'hidden' BOOL;

update resource set hidden = false;