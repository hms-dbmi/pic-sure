CREATE TABLE user_consents (
   `uuid` binary(16) NOT NULL UNIQUE,
   `user_id` binary(16) NOT NULL UNIQUE,
   `consents` JSON,
   FOREIGN KEY (`user_id`) REFERENCES user(`uuid`)
       ON DELETE CASCADE
);