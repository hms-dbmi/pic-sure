USE `auth`;

ALTER TABLE `user` MODIFY auth0_metadata LONGTEXT;
ALTER TABLE `user` MODIFY general_metadata LONGTEXT;
