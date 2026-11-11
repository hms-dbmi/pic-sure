package edu.harvard.hms.dbmi.avillach.ai;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;
import edu.harvard.hms.dbmi.avillach.ai.config.CallerContextArgumentResolver;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Mechanically enforces Story 3's "one isolated module" AC: nothing outside {@code ai.model} may reference a Bedrock- or
 * Spring-AI-model-specific type, so swapping Bedrock Converse for another provider later (per {@code PLAN.md}'s portability table) stays a
 * change to that one package.
 */
class ArchitectureTest {

    private static final String ROOT = "edu.harvard.hms.dbmi.avillach.ai";
    private static final String MODEL = ROOT + ".model..";
    private static final String MCP_GATEWAY = ROOT + ".mcp.gateway..";

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importMainClasses() {
        mainClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
    }

    @Test
    void onlyModelPackageTouchesBedrockOrSpringAi() {
        noClasses().that().resideOutsideOfPackages(MODEL).should()
            .dependOnClassesThat(resideInAnyPackage("org.springframework.ai..", "software.amazon.awssdk.."))
            .because(
                "Bedrock/Spring-AI-model types must stay confined to ai.model, so the rest of the service never "
                    + "references the model provider directly and a future provider swap touches one package"
            ).check(mainClasses);
    }

    /**
     * Guards "the service never mints, caches, or exchanges a session token of its own". Every outbound HTTP call lives in one of two
     * packages: {@code ai.mcp.gateway} (replays the caller's token, covered at the wire by {@code GatewayMcpToolGatewayTest}) and
     * {@code ai.model} (uses the model provider's own credential). A new HTTP client anywhere else, such as one calling PSAMA for a token,
     * fails here and has to be added on purpose.
     */
    @Test
    void outboundHttpIsConfinedToTheMcpGatewayAndModelPackages() {
        noClasses().that().resideOutsideOfPackages(MCP_GATEWAY, MODEL).should().dependOnClassesThat(
            resideInAnyPackage(
                "org.springframework.web.client..", "org.springframework.web.reactive.function.client..", "java.net.http..", "org.apache.hc..",
                "org.apache.http..", "okhttp3.."
            ).or(equivalentTo(java.net.HttpURLConnection.class))
        ).because("an outbound call outside these packages could be a token-issuing or token-exchange call").check(mainClasses);
    }

    @Test
    void noTokenMintingOrExchangeLibrariesAreUsed() {
        noClasses().should().dependOnClassesThat(
            resideInAnyPackage("io.jsonwebtoken..", "com.auth0.jwt..", "com.nimbusds..", "org.springframework.security.oauth2..")
        ).because("the service neither signs, parses for re-issue, nor exchanges tokens; it only replays the inbound header").check(mainClasses);
    }

    /** The model provider gets its own credential, so the model package must not even be able to see the PIC-SURE caller's token. */
    @Test
    void modelPackageNeverSeesTheCallersIdentity() {
        noClasses().that().resideInAPackage(MODEL).should().dependOnClassesThat().areAssignableTo(CallerContext.class)
            .because("the caller's PIC-SURE token must never be sent to the model provider").check(mainClasses);
    }

    /** Only the MCP gateway may write the caller's token onto an outbound request, and only the argument resolver may read it. */
    @Test
    void onlyTheMcpGatewayForwardsAndOnlyTheResolverReadsTheCallersToken() {
        noClasses().that().resideOutsideOfPackage(MCP_GATEWAY).and().areNotAssignableTo(CallerContext.class).should()
            .callMethod(CallerContext.class, "applyTo", HttpHeaders.class)
            .because("forwarding the caller's token is the MCP gateway's job alone").check(mainClasses);
        noClasses().that().areNotAssignableTo(CallerContext.class).and().areNotAssignableTo(CallerContextArgumentResolver.class).should()
            .callMethod(CallerContext.class, "authorization")
            .because("nothing else has a reason to read the raw token, which is how it ends up logged, stored or sent somewhere new")
            .check(mainClasses);
    }
}
