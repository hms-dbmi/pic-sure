USE `picsure`;

CREATE TABLE `site` (
    `uuid` binary(16) NOT NULL,
    `code` varchar(15) COLLATE utf8_bin DEFAULT NULL,
    `name` varchar(255) COLLATE utf8_bin DEFAULT NULL,
    `domain` varchar(255) COLLATE utf8_bin DEFAULT NULL,
    PRIMARY KEY (`uuid`),
    CONSTRAINT `unique_code` UNIQUE (`code`),
    CONSTRAINT `unique_domain` UNIQUE (`domain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;
