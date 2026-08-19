package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.junit.jupiter.api.Test;

/**
 * Regression for ALS-12766 ("Data Dashboard Drawer Fails to Load").
 *
 * <p>{@link DashboardDrawerController#findByDatasetId(Integer)} declares {@code @PathVariable Integer id} with no explicit name, so Spring
 * MVC can only bind it if the parameter name survives compilation — i.e. the module is compiled with javac's {@code -parameters} flag.
 * spring-boot-starter-parent supplies that flag by default, but these modules do not inherit it; it is instead configured on the reactor's
 * maven-compiler-plugin (see the root pom). If that configuration is lost, the name is stripped and every request to {@code GET
 * /dashboard-drawer/{id}} fails at bind time with
 * {@code IllegalArgumentException: Name for argument of type [java.lang.Integer] not specified} and returns HTTP 500 before the controller
 * body runs.
 *
 * <p>This asserts the compiled bytecode still carries the parameter name, so the flag cannot be dropped again without a test turning red.
 * (The module's other controller tests invoke the methods directly and never exercise {@code @PathVariable} binding, which is why the
 * original break went unnoticed.)
 */
class DashboardDrawerControllerParameterNameTest {

    @Test
    void pathVariableNameSurvivesCompilation() throws NoSuchMethodException {
        Method method = DashboardDrawerController.class.getDeclaredMethod("findByDatasetId", Integer.class);
        Parameter parameter = method.getParameters()[0];

        assertTrue(
            parameter.isNamePresent(),
            "@PathVariable parameter name was stripped at compile time — the maven-compiler-plugin "
                + "'-parameters' flag is missing, so Spring cannot bind GET /dashboard-drawer/{id} (ALS-12766)."
        );
        assertEquals("id", parameter.getName(), "Parameter name must match the {id} path template variable.");
    }
}
