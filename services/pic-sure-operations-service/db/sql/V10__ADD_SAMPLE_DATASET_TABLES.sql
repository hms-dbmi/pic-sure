USE `picsure`;

CREATE TABLE `query_to_variant` (
    `uuid` binary(16) NOT NULL,
    `user_uuid` binary(16) NOT NULL,
    `created_at` datetime(3) NOT NULL,
    PRIMARY KEY (`uuid`),
    KEY `idx_query_to_variant_user_uuid` (`user_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin;

ALTER TABLE `query`
    ADD COLUMN `user_uuid` binary(16) NULL,
    ADD COLUMN `consents` JSON NULL,
    ADD COLUMN `query_to_variant_uuid` binary(16) NULL,
    ADD COLUMN `sample` bit(1) NOT NULL DEFAULT 0,
    ADD COLUMN `result_type` varchar(64) COLLATE utf8_bin NULL,
    ALGORITHM=INSTANT;

ALTER TABLE `query`
    ADD INDEX `idx_query_user_uuid` (`user_uuid`),
    ADD UNIQUE KEY `unique_query_variant` (`query_to_variant_uuid`, `sample`, `result_type`),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE `named_dataset`
    ADD COLUMN `query_to_variant_uuid` binary(16) NULL,
    ALGORITHM=INSTANT;

ALTER TABLE `named_dataset`
    ADD INDEX `idx_named_dataset_query_to_variant` (`query_to_variant_uuid`),
    ALGORITHM=INPLACE, LOCK=NONE;

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
