package edu.harvard.hms.dbmi.avillach.auth.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

/**
 * The api-conventions rules take the known authority names from {@link AuthNaming.AuthRoleNaming}'s field names, so each constant must be
 * named exactly as its value.
 */
class AuthNamingTest {

    @Test
    void everyAuthorityConstantIsNamedAsItsValue() throws IllegalAccessException {
        for (Field field : AuthNaming.AuthRoleNaming.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                assertThat(field.get(null)).as(field.getName()).isEqualTo(field.getName());
            }
        }
    }
}
