package edu.harvard.dbmi.avillach.logging;

import edu.harvard.dbmi.avillach.logging.config.AppConfig;
import edu.harvard.dbmi.avillach.logging.handler.AuditHandler;
import edu.harvard.dbmi.avillach.logging.handler.HealthHandler;
import edu.harvard.dbmi.avillach.logging.middleware.ApiKeyAuthMiddleware;
import edu.harvard.dbmi.avillach.logging.service.AuditLogService;
import edu.harvard.dbmi.avillach.logging.service.JwtDecodeService;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        AtomicBoolean readiness = new AtomicBoolean(false);
        Javalin app = createApp(config, readiness);
        app.start(config.port());
        readiness.set(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            readiness.set(false);
            app.stop();
        }));

        log.info("Audit logging service started: app={}, platform={}, environment={}, hostname={}, port={}, allowedOrigin={}",
            config.app(), config.platform(), config.environment(), config.hostname(), config.port(), config.allowedOrigin());
    }

    public static Javalin createApp(AppConfig config, AtomicBoolean readiness) {
        JwtDecodeService jwtDecodeService = new JwtDecodeService(config.jwtClaimMapping());
        AuditLogService auditLogService = new AuditLogService(config, jwtDecodeService);
        AuditHandler auditHandler = new AuditHandler(auditLogService);
        HealthHandler healthHandler = new HealthHandler(readiness);
        ApiKeyAuthMiddleware authMiddleware = new ApiKeyAuthMiddleware(config.auditApiKey());

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.http.maxRequestSize = 1_048_576L; // 1MB
            javalinConfig.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    if ("*".equals(config.allowedOrigin())) {
                        rule.anyHost();
                    } else {
                        rule.allowHost(config.allowedOrigin());
                    }
                });
            });
        });

        app.before("/audit", authMiddleware::authenticate);

        app.post("/audit", auditHandler::handle);
        app.get("/health", healthHandler::handle);

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception", e);
            ctx.status(500);
            ctx.json(Map.of("status", "error", "message", "Internal server error"));
        });

        return app;
    }
}
