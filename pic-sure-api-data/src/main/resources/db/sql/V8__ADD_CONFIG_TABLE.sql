USE `picsure`;

CREATE TABLE `configuration` (
    `uuid` binary(16) NOT NULL UNIQUE,
    `name` varchar(255) COLLATE utf8_bin NOT NULL UNIQUE,
    `kind` varchar(255) COLLATE utf8_bin NOT NULL,
    `value` TEXT NOT NULL,
    `description` varchar(255) COLLATE utf8_bin DEFAULT NULL,
    PRIMARY KEY (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;
