USE `picsure`;

CREATE TABLE `named_dataset` (
    `uuid` binary(16) NOT NULL,
    `queryId` binary(16) NOT NULL,
    `user` varchar(255) COLLATE utf8_bin DEFAULT NULL,
    `name` varchar(255) COLLATE utf8_bin DEFAULT NULL,
    `archived` bit(1) NOT NULL DEFAULT FALSE,
    `metadata` TEXT,
    PRIMARY KEY (`uuid`),
    CONSTRAINT `foreign_queryId` FOREIGN KEY (`queryId`) REFERENCES `query` (`uuid`),
    CONSTRAINT `unique_queryId_user` UNIQUE (`queryId`, `user`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;
