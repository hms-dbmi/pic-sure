USE `auth`;

DROP PROCEDURE IF EXISTS CreateUserWithRole;
DELIMITER //
CREATE PROCEDURE CreateUserWithRole (
    IN user_email VARCHAR(255),
    IN connection_id VARCHAR(255),
    IN role_name VARCHAR(255),
    IN user_general_metadata varchar(255)
)
BEGIN
    -- Attempt to retrieve the UUIDs for the user and role based on the provided information
SELECT @userUUID := uuid FROM auth.user WHERE email = user_email AND connectionId = connection_id;
SELECT @roleUUID := uuid FROM auth.role WHERE name = role_name;

-- If the user does not exist, create a new user entry
IF @userUUID IS NULL THEN
        -- Generate a new UUID for the user
        SET @userUUID = UNHEX(REPLACE(UUID(), '-', ''));
        -- Retrieve the UUID for the connection
SELECT @connectionUUID := uuid FROM auth.connection WHERE id = connection_id;
-- Insert the new user record into the user table
INSERT INTO auth.user (uuid, general_metadata, acceptedTOS, connectionId, email, matched, subject, is_active, long_term_token)
VALUES (@userUUID, user_general_metadata, CURRENT_TIMESTAMP, @connectionUUID, user_email, 0, NULL, 1, NULL);
END IF;

    -- If the role exists, associate the user with the role
    IF @roleUUID IS NOT NULL THEN
        INSERT INTO auth.user_role (user_id, role_id) VALUES (@userUUID, @roleUUID);
END IF;
END//
DELIMITER ;