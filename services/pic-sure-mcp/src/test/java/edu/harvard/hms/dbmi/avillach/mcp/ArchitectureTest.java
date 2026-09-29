package edu.harvard.hms.dbmi.avillach.mcp;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayClientConfig;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URLConnection;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture rules that hold the service to the open channel and to audited tools. The service reaches PIC-SURE data only through the
 * gateway's open channel, with one outbound HTTP client, no Spring Security and no database, and every tool call sends an audit event.
 * These rules fail the build when a change would break any of that, instead of leaving it to review.
 *
 * <p>The main classes are imported with ArchUnit, tests excluded. ArchUnit does not expose the string literals in a class's constant pool,
 * so the channel rule reads the compiled class files under {@code target/classes} directly and scans their UTF8 constant entries.
 */
class ArchitectureTest {

    private static final String ROOT = "edu.harvard.hms.dbmi.avillach.mcp";
    private static final String TOOL = ROOT + ".tool..";
    private static final String GATEWAY = ROOT + ".gateway..";
    private static final String QUERY = ROOT + ".query..";
    private static final String CALLER = ROOT + ".caller..";
    private static final String CONFIG = ROOT + ".config..";
    private static final String AUDIT = ROOT + ".audit..";

    private static final String AUTHORIZED_PATH = "/hpds/auth";
    private static final String BACKEND_TEMPLATE = "{backend}";
    private static final String OPEN_PREFIX = "/hpds/open/";
    private static final String OPEN_QUERY_SYNC_PATH = "/hpds/open/query/sync";
    private static final List<String> TRAVERSAL_MARKERS = List.of("/..", "../", "%2e");
    private static final Pattern RESOURCE_PATH = Pattern.compile("(?:^|[\\s:=\"'(,\\[])(/[^\\s\"',)\\]]*)");

    private static final String REST_CLIENT = "org.springframework.web.client.RestClient";
    private static final Set<String> FORBIDDEN_HTTP_CLIENTS =
        Set.of("org.springframework.web.client.RestTemplate", "javax.net.ssl.HttpsURLConnection", REST_CLIENT + "$Builder");
    private static final String[] FORBIDDEN_HTTP_PACKAGES = {"org.springframework.http.client..", "org.springframework.boot.http.client..",
        "java.net.http..", "org.springframework.web.reactive.function.client.."};
    private static final Set<String> REQUEST_METHODS = Set.of("get", "head", "post", "put", "patch", "delete", "options", "method");

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importMainClasses() {
        mainClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
    }

    /**
     * No compiled class carries a string constant that names the authorized query channel or a backend template, and every path constant
     * that names HPDS or a query sits under {@code /hpds/open/}. A path here means a UTF8 constant starting with {@code /}: internal class
     * names start with a letter and descriptors with {@code (}, {@code L}, or {@code [}, so they never match. This keeps the service from
     * building a relative gateway URL that reaches authorized, unobfuscated data. Because the prefix check reads unnormalized text, any
     * constant containing a dot segment ({@code /..}, {@code ../}, or a percent-encoded dot {@code %2e}, in any case) also fails, so
     * {@code /hpds/open/../auth} cannot pass as an open path. An absolute URL to another host is outside this check and is refused at
     * runtime by the gateway client's interceptor.
     *
     * <p>The class files are read byte by byte because a literal inlined into a method body, or held in an annotation value, is visible
     * only in the constant pool. Every UTF8 entry is checked, not only those a string constant points at. As a control, the scan must have
     * read {@link OpenQueryClient} and found {@code /hpds/open/query/sync} in it, so it cannot pass having scanned nothing.
     */
    @Test
    void noClassNamesTheAuthorizedChannel() throws IOException {
        Path classes = mainClassesDirectory();
        List<String> violations = new ArrayList<>();
        boolean sawOpenQueryPath = false;
        String openQueryClientFile = OpenQueryClient.class.getName().replace('.', '/') + ".class";
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String relative = classes.relativize(file).toString().replace('\\', '/');
                for (String constant : utf8Constants(Files.readAllBytes(file))) {
                    if (namesAuthorizedChannel(constant) || isNonOpenQueryPath(constant) || containsTraversal(constant)) {
                        violations.add(relative + ": \"" + constant + "\"");
                    }
                    if (relative.equals(openQueryClientFile) && constant.equals(OPEN_QUERY_SYNC_PATH)) {
                        sawOpenQueryPath = true;
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "The service may call only the open channel under " + OPEN_PREFIX + ". These class constants name " + AUTHORIZED_PATH + ", a "
                + BACKEND_TEMPLATE + " template, an HPDS or query path outside " + OPEN_PREFIX + ", or a dot segment (/.., ../, %2e): "
                + violations
        );
        assertTrue(
            sawOpenQueryPath,
            "The class scan did not find " + OPEN_QUERY_SYNC_PATH + " in " + openQueryClientFile + " under " + classes
                + ", so it cannot be trusted to have read the service's classes"
        );
    }

    /**
     * No main resource file names the authorized query channel or a backend template, and every path in a resource that names HPDS or a
     * query sits under {@code /hpds/open/}, so configuration cannot point a client at authorized data either. A path is a token starting
     * with {@code /} after the start of a line, whitespace, or a separator. A resource containing a dot segment ({@code /..}, {@code ../},
     * or {@code %2e}, in any case) also fails, so a path cannot climb out of {@code /hpds/open/}. As a control, the scan must have read
     * {@code application.yml}.
     */
    @Test
    void noResourceNamesTheAuthorizedChannel() throws IOException {
        Path resources = mainClassesDirectory().getParent().getParent().resolve("src/main/resources");
        List<String> violations = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = resources.relativize(file).toString().replace('\\', '/');
                scanned.add(name);
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (text.contains(AUTHORIZED_PATH) || text.contains(BACKEND_TEMPLATE) || containsTraversal(text)) {
                    violations.add(name);
                }
                Matcher paths = RESOURCE_PATH.matcher(text);
                while (paths.find()) {
                    if (isNonOpenQueryPath(paths.group(1))) {
                        violations.add(name + ": " + paths.group(1));
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Configuration may not name " + AUTHORIZED_PATH + ", a " + BACKEND_TEMPLATE + " template, an HPDS or query path outside "
                + OPEN_PREFIX + ", or a dot segment (/.., ../, %2e), because the service reaches only the open channel. Found in: "
                + violations
        );
        assertTrue(
            scanned.contains("application.yml"),
            "The resource scan did not read application.yml under " + resources + ", so it cannot be trusted; it read " + scanned
        );
    }

    /**
     * Only {@link GatewayClientConfig}, and the {@link GatewayRequestInterceptor} it installs, may touch HTTP client machinery, so the
     * gateway client, bound to {@code picsure.mcp.gateway-url} and guarded by its interceptor against other hosts and redirects, stays the
     * one way out of the service. Banned: the Spring and Spring Boot HTTP client packages (request factories, interceptors, and settings),
     * {@code java.net.http}, WebClient, {@code RestTemplate}, {@code RestClient.Builder}, anything assignable to {@code URLConnection} or
     * {@code Socket}, and the static {@code RestClient} factories and {@code URL} connection methods. The {@code RestClient} exception
     * types live in {@code org.springframework.web.client} and stay allowed.
     */
    @Test
    void onlyGatewayClientConfigBuildsAnHttpClient() {
        noClasses().that().doNotBelongToAnyOf(GatewayClientConfig.class, GatewayRequestInterceptor.class).should()
            .dependOnClassesThat(
                resideInAnyPackage(FORBIDDEN_HTTP_PACKAGES).or(assignableTo(URLConnection.class)).or(assignableTo(Socket.class))
                    .or(DescribedPredicate.describe("are HTTP client types", c -> FORBIDDEN_HTTP_CLIENTS.contains(c.getName())))
            ).orShould().callMethodWhere(DescribedPredicate.describe("builds a RestClient or opens a URL connection", call -> {
                String owner = call.getTargetOwner().getName();
                String name = call.getName();
                return (owner.equals(REST_CLIENT) && (name.equals("builder") || name.equals("create")))
                    || (owner.equals("java.net.URL") && (name.equals("openConnection") || name.equals("openStream")));
            })).because("the gateway RestClient built in GatewayClientConfig is the only outbound client the service may have")
            .check(mainClasses);
    }

    /**
     * Classes outside the gateway clients and their configuration do not touch {@code RestClient}, so every tool reaches the gateway
     * through a client that fixes its path, and none can issue an arbitrary request.
     */
    @Test
    void onlyGatewayClientsUseTheRestClient() {
        noClasses().that().resideOutsideOfPackages(GATEWAY, CONFIG).should()
            .dependOnClassesThat(
                DescribedPredicate.describe(
                    "are RestClient or its nested types", c -> c.getName().equals(REST_CLIENT) || c.getName().startsWith(REST_CLIENT + "$")
                )
            ).because("tools must call the gateway through DictionaryClient or OpenQueryClient, whose paths are fixed").check(mainClasses);
    }

    /**
     * Only {@link OpenQueryClient} and {@link DictionaryClient} start a {@code RestClient} request, so no other class, in {@code gateway}
     * or {@code config}, can assemble a gateway URL. Those two clients hold every path the service calls as a fixed constant.
     */
    @Test
    void onlyTheTwoGatewayClientsStartRequests() {
        noClasses().that().doNotBelongToAnyOf(OpenQueryClient.class, DictionaryClient.class).should()
            .callMethodWhere(
                DescribedPredicate.describe(
                    "starts a RestClient request",
                    (JavaAccess<?> call) -> call.getTargetOwner().getName().equals(REST_CLIENT) && REQUEST_METHODS.contains(call.getName())
                )
            ).because("the open query and dictionary clients are the only classes that choose a gateway path").check(mainClasses);
    }

    /**
     * No configuration class calls a method on a gateway client. Tools are the only callers of {@link OpenQueryClient} and
     * {@link DictionaryClient}, and every tool call passes the audit aspect, so a query or dictionary call made from configuration would
     * reach the gateway with no audit event. Constructor calls and field reads are not method calls and are not checked, so
     * {@link GatewayClientConfig} building the {@code RestClient} and reading the path constants still passes.
     */
    @Test
    void configNeverCallsTheGatewayClients() {
        noClasses().that().resideInAPackage(CONFIG).should()
            .callMethodWhere(
                DescribedPredicate.describe(
                    "the target is in the gateway package", (JavaAccess<?> call) -> resideInAPackage(GATEWAY).test(call.getTargetOwner())
                )
            ).because("a gateway call made from configuration would bypass the audited tool handlers").check(mainClasses);
    }

    /**
     * No class calls {@code RestClient.mutate}, which would copy the gateway client into one with a different base URL or without the
     * interceptor that refuses other hosts.
     */
    @Test
    void noClassMutatesTheRestClient() {
        noClasses().should()
            .callMethodWhere(
                DescribedPredicate.describe(
                    "RestClient.mutate",
                    (JavaAccess<?> call) -> call.getTargetOwner().getName().equals(REST_CLIENT) && call.getName().equals("mutate")
                )
            ).because("a mutated gateway client could drop the base URL or the host guard").check(mainClasses);
    }

    /**
     * Every {@code @McpTool} method carries {@code @AuditEvent} and is public, non-static, and non-final, so the audit aspect, applied by a
     * CGLIB proxy that cannot advise static or final methods, sends an event for each dictionary tool call.
     */
    @Test
    void everyMcpToolMethodIsAudited() {
        methods().that().areAnnotatedWith(McpTool.class).should().beAnnotatedWith(AuditEvent.class).andShould().bePublic().andShould()
            .notBeStatic().andShould().notBeFinal()
            .because("the gateway audits POST /mcp as one event, so the tool event is the only record of which tool ran")
            .check(mainClasses);
    }

    /**
     * Every {@code handle} method on a tool class carries {@code @AuditEvent} and is public, non-static, and non-final, so the audit
     * aspect, applied by a CGLIB proxy that cannot advise static or final methods, sends an event for each query tool call registered as a
     * tool specification.
     */
    @Test
    void everyToolHandleMethodIsAudited() {
        methods().that().haveName("handle").and().areDeclaredInClassesThat(toolClasses()).should().beAnnotatedWith(AuditEvent.class)
            .andShould().bePublic().andShould().notBeStatic().andShould().notBeFinal()
            .because("the gateway audits POST /mcp as one event, so the tool event is the only record of which tool ran")
            .check(mainClasses);
    }

    /**
     * Every tool bean (a {@code *Tool} class in {@code tool} that is a Spring component, directly or through a stereotype such as
     * {@code @Service}) has at least one handler the aspect can advise: an {@code @McpTool} method or a {@code handle} method that is
     * public, non-static, non-final, and carries {@code @AuditEvent}. It proves a tool bean has an audited entry point, not that every
     * entry point is audited; {@link #configCallsOnlyAuditedToolMethods} covers the calls configuration makes.
     */
    @Test
    void everyToolComponentHasAnAuditedHandler() {
        classes().that(toolClasses()).and().areMetaAnnotatedWith(Component.class).should(haveAnAuditedHandler())
            .because("a tool bean without an advisable @AuditEvent handler would run without an audit event").check(mainClasses);
    }

    /**
     * Every method that configuration calls on a tool class carries {@code @AuditEvent}. Query tools are registered by tool specification
     * beans in {@code config}, whose handlers call into the tool bean, so this pins that each registered handler goes through an audited
     * method and not some other public method on the same bean. Constructor calls and field reads are not method calls and are not checked.
     */
    @Test
    void configCallsOnlyAuditedToolMethods() {
        noClasses().that().resideInAPackage(CONFIG).should()
            .callMethodWhere(DescribedPredicate.describe("the target is a tool class method without @AuditEvent", call -> {
                if (!toolClasses().test(call.getTargetOwner())) {
                    return false;
                }
                return call.getTarget().resolveMember().map(m -> !m.isAnnotatedWith(AuditEvent.class)).orElse(true);
            })).because("a tool specification whose handler calls an unaudited tool method would run without an audit event")
            .check(mainClasses);
    }

    /**
     * No class depends on Spring Security, JDBC, or JPA. The service authenticates nobody itself and keeps no data: the gateway verifies
     * the caller and the downstream services apply access, so security or persistence code here would be a second, unreviewed policy.
     */
    @Test
    void noSecurityOrPersistence() {
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.security..", "javax.sql..", "java.sql..", "jakarta.persistence..", "org.springframework.jdbc.."
            ).because("the service has no Spring Security and no database; the gateway and downstream services own both")
            .check(mainClasses);
    }

    /**
     * Tool classes depend only on the gateway clients, the query model, and the caller headers among the service's own packages, so a tool
     * cannot reach configuration or the audit aspect directly.
     */
    @Test
    void toolsDependOnlyOnClientsQueryAndCaller() {
        noClasses().that().resideInAPackage(TOOL).should()
            .dependOnClassesThat(
                resideInAPackage(ROOT + "..").and(DescribedPredicate.not(resideInAnyPackage(TOOL, GATEWAY, QUERY, CALLER)))
            ).because("tools go through the gateway clients and never through configuration or the audit aspect").check(mainClasses);
    }

    /**
     * Only configuration depends on the audit package. The aspect is wired as a bean and applied by proxy, so no code calls it and none can
     * bypass it by calling it selectively.
     */
    @Test
    void auditIsOnlyWiredByConfig() {
        noClasses().that().resideOutsideOfPackages(AUDIT, CONFIG).should().dependOnClassesThat().resideInAPackage(AUDIT)
            .because("the audit aspect is applied by proxy around every tool call, not called by tools").check(mainClasses);
    }

    private static DescribedPredicate<JavaClass> toolClasses() {
        return DescribedPredicate.describe(
            "are tool classes",
            c -> (c.getPackageName().equals(ROOT + ".tool") || c.getPackageName().startsWith(ROOT + ".tool."))
                && c.getSimpleName().endsWith("Tool")
        );
    }

    private static ArchCondition<JavaClass> haveAnAuditedHandler() {
        return new ArchCondition<>(
            "have an @AuditEvent handler, an @McpTool method or a handle method, that is public, non-static, and non-final"
        ) {
            @Override
            public void check(JavaClass tool, ConditionEvents events) {
                boolean audited =
                    tool.getMethods().stream().filter(ArchitectureTest::isToolHandler).anyMatch(m -> m.isAnnotatedWith(AuditEvent.class));
                events.add(new SimpleConditionEvent(tool, audited, tool.getName() + " has no audited tool handler"));
            }
        };
    }

    private static boolean isToolHandler(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        boolean advisable =
            modifiers.contains(JavaModifier.PUBLIC) && !modifiers.contains(JavaModifier.STATIC) && !modifiers.contains(JavaModifier.FINAL);
        return advisable && (method.isAnnotatedWith(McpTool.class) || method.getName().equals("handle"));
    }

    private static boolean containsTraversal(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return TRAVERSAL_MARKERS.stream().anyMatch(lower::contains);
    }

    private static boolean isNonOpenQueryPath(String constant) {
        if (!constant.startsWith("/")) {
            return false;
        }
        String lower = constant.toLowerCase(Locale.ROOT);
        return (lower.contains("hpds") || lower.contains("query")) && !constant.startsWith(OPEN_PREFIX);
    }

    private static boolean namesAuthorizedChannel(String constant) {
        return constant.contains(AUTHORIZED_PATH) || constant.contains(BACKEND_TEMPLATE);
    }

    private static Path mainClassesDirectory() {
        try {
            return Path.of(McpApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the UTF8 entries of a class file's constant pool, which hold every string literal, class name, member name, and descriptor.
     *
     * @param classFile the class file bytes
     * @return the decoded UTF8 constants
     * @throws IOException if the class file is truncated or has an unknown constant tag
     */
    static List<String> utf8Constants(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("Not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        List<String> constants = new ArrayList<>();
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> constants.add(in.readUTF());
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    index++;
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        return constants;
    }
}
