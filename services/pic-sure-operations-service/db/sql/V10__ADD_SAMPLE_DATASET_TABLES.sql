USE `picsure`;

ALTER TABLE `query`
    ADD COLUMN `user_uuid` binary(16) NULL,
    ADD COLUMN `consents` JSON NULL,
    ADD INDEX `idx_query_user_uuid` (`user_uuid`);

CREATE TABLE `query_run` (
    `run_id` binary(16) NOT NULL,
    `query_uuid` binary(16) NOT NULL,
    `consents` JSON NULL,
    `trigger` varchar(32) COLLATE utf8_bin NOT NULL,
    `submitted_at` datetime(3) NOT NULL,
    `completed_at` datetime(3) NULL,
    `status` int(11) NULL,
    PRIMARY KEY (`run_id`),
    KEY `idx_query_run_query_uuid` (`query_uuid`),
    CONSTRAINT `fk_query_run_query` FOREIGN KEY (`query_uuid`) REFERENCES `query` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;

CREATE TABLE `named_dataset_query` (
    `named_dataset_uuid` binary(16) NOT NULL,
    `query_uuid` binary(16) NOT NULL,
    `sample` bit(1) NOT NULL DEFAULT 0,
    `result_type` varchar(64) COLLATE utf8_bin NULL,
    `created_at` datetime(3) NOT NULL,
    PRIMARY KEY (`named_dataset_uuid`, `query_uuid`),
    CONSTRAINT `unique_named_dataset_variant` UNIQUE (`named_dataset_uuid`, `sample`, `result_type`),
    KEY `idx_named_dataset_query_query_uuid` (`query_uuid`),
    CONSTRAINT `fk_named_dataset_query_dataset` FOREIGN KEY (`named_dataset_uuid`) REFERENCES `named_dataset` (`uuid`) ON DELETE CASCADE,
    CONSTRAINT `fk_named_dataset_query_query` FOREIGN KEY (`query_uuid`) REFERENCES `query` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;

INSERT INTO `named_dataset_query` (`named_dataset_uuid`, `query_uuid`, `sample`, `result_type`, `created_at`)
SELECT nd.`uuid`, nd.`queryId`, 0, NULL, COALESCE(q.`startTime`, CURRENT_TIMESTAMP(3))
FROM `named_dataset` nd
JOIN `query` q ON q.`uuid` = nd.`queryId`;
