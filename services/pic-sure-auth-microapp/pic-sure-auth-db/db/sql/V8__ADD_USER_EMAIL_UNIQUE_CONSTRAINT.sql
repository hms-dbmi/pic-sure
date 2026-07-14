USE `auth`;

ALTER TABLE `user` ADD CONSTRAINT `email_UNIQUE` UNIQUE (`email`, `connectionId`);
