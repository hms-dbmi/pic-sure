USE `auth`;

DROP PROCEDURE IF EXISTS CreateUserWithRole;
DELIMITER //
CREATE PROCEDURE CreateUserWithRole(
    IN user_email VARCHAR(255),
    IN connection_id VARCHAR(255),
    IN role_name VARCHAR(255),
    IN user_general_metadata varchar(255)
)
BEGIN
    -- Attempt to retrieve the UUIDs for the user and role based on the provided information
    SELECT @userUUID := uuid FROM auth.user WHERE email = user_email AND connectionId = connection_id;
    SELECT @roleUUID := uuid FROM auth.role WHERE name = role_name;
    SELECT @picsureUserRoleId := uuid FROM auth.role WHERE name = 'PIC-SURE User';

-- If the user does not exist, create a new user entry
    IF @userUUID IS NULL THEN
        set @baseUUID = UUID();
        -- Generate a new UUID for the user
        SET @userUUID = UNHEX(REPLACE(@baseUUID, '-', ''));
        -- Retrieve the UUID for the connection
        SELECT @connectionUUID := uuid FROM auth.connection WHERE id = connection_id;
        SELECT @connectionSubPrefix := subPrefix FROM auth.connection WHERE id = connection_id;
-- Insert the new user record into the user table
        INSERT INTO auth.user (uuid, general_metadata, acceptedTOS, connectionId, email, matched, subject, is_active,
                               long_term_token)
        VALUES (@userUUID, user_general_metadata, CURRENT_TIMESTAMP, @connectionUUID, user_email, 0,
                concat(@connectionSubPrefix, REPLACE(@baseUUID, '-', '')), 1, NULL);
    END IF;

    -- If the role exists, associate the user with the role
    IF @roleUUID IS NOT NULL THEN
        INSERT INTO auth.user_role (user_id, role_id) VALUES (@userUUID, @roleUUID);
    END IF;

    -- If the role is not PIC-SURE User, associate the user with the PIC-SURE User role as well
    -- All users must have the PIC-SURE User role
    IF @roleUUID IS NOT NULL AND @roleUUID != @picsureUserRoleId THEN
        INSERT INTO auth.user_role (user_id, role_id) VALUES (@userUUID, @picsureUserRoleId);
    END IF;
END//
DELIMITER ;