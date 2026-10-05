CREATE TABLE api_key (
   `uuid` binary(16) NOT NULL,
   `key_hash` varchar(64) NOT NULL,
   `hash_scheme` varchar(16) NOT NULL,
   `display_prefix` varchar(8) NOT NULL,
   `key_type` varchar(16) NOT NULL,
   `name` varchar(255),
   `email` varchar(255),
   `created_at` datetime NOT NULL,
   `expires_at` datetime,
   `revoked_at` datetime,
   `last_used_at` datetime,
   PRIMARY KEY (`uuid`),
   UNIQUE KEY `uk_api_key_hash` (`key_hash`),
   KEY `idx_api_key_key_type_created_at` (`key_type`, `created_at`),
   -- NULL expires_at means "never expires" and is an explicit opt-in for PLATFORM keys only
   CONSTRAINT `ck_api_key_user_key_expires` CHECK (`key_type` = 'PLATFORM' OR `expires_at` IS NOT NULL)
);
