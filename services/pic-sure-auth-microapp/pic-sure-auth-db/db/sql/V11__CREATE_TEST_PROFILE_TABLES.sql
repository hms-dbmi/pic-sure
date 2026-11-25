CREATE TABLE test_profile (
    `uuid` binary(16) NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    `name` varchar(255) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    -- JSON array of consent strings, e.g. ["phs000007.c1", "phs000179.c2"]
    `consents` JSON NOT NULL,
    PRIMARY KEY (`uuid`),
    CONSTRAINT `test_profile_consents_must_be_json_array_of_strings`
        CHECK (JSON_SCHEMA_VALID('{"type": "array", "items": {"type": "string"}}', `consents`))
);

CREATE TABLE user_test_profile (
    `uuid` binary(16) NOT NULL DEFAULT (UUID_TO_BIN(UUID())),
    `user_id` binary(16) NOT NULL UNIQUE,
    `test_profile_id` binary(16) NOT NULL,
    `enabled` bit(1) NOT NULL DEFAULT b'1',
    PRIMARY KEY (`uuid`),
    FOREIGN KEY (`user_id`) REFERENCES user(`uuid`)
        ON DELETE CASCADE,
    FOREIGN KEY (`test_profile_id`) REFERENCES test_profile(`uuid`)
        ON DELETE CASCADE
);
