USE `picsure`;

CREATE TABLE `configuration` (
    `uuid` binary(16) NOT NULL UNIQUE DEFAULT (UNHEX(REPLACE(UUID(),'-',''))),
    `name` varchar(255) COLLATE utf8_bin NOT NULL,
    `kind` varchar(255) COLLATE utf8_bin NOT NULL,
    `value` TEXT NOT NULL,
    `description` varchar(255) COLLATE utf8_bin DEFAULT '',
    `delete` bit(1) NOT NULL DEFAULT FALSE,
    PRIMARY KEY (`uuid`),
    CONSTRAINT `unique_name_kind` UNIQUE (`name`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;
