package edu.harvard.hms.dbmi.avillach.mcp.audit;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.tool.CountTool;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import io.modelcontextprotocol.common.McpTransportContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sends one audit event for every call to a tool method annotated with {@link AuditEvent}, and binds the caller's request ID into the MDC
 * for the length of the call so every log line inside it carries the ID.
 *
 * <p>The event carries the annotation's type and action, an {@code outcome} of {@code success} or {@code failure}, the request ID, the
 * gateway's {@code X-User-Id} as {@code user_id}, and only these argument fields: {@code search} (search text), {@code terms} (the search
 * terms joined with {@code |}), {@code page}, {@code page_size}, {@code dataset}, {@code concept_path}, {@code concept_paths} (the concept
 * paths joined with {@code |}), and {@code result_type} for the count tools and {@code get_adapter_code}. Each text field is cut at
 * {@value #MAX_FIELD_LENGTH} characters. Nothing from a query body, and never a credential, is read. A failure event also carries
 * {@code error.error_type} of {@code tool_failure} or {@code internal}, and no message.
 *
 * <p>Sending is fire-and-forget. A failure to build or send an event is logged once at WARN with its exception class and never reaches the
 * tool call.
 */
@Aspect
@Component
public class ToolAuditAspect {

    /** Longest string copied from an argument into an event, in characters. */
    static final int MAX_FIELD_LENGTH = 500;

    private static final Set<String> TEXT_PARAMETERS = Set.of("search", "dataset", "conceptPath");
    private static final Set<String> LIST_PARAMETERS = Set.of("terms", "conceptPaths");
    private static final String LIST_SEPARATOR = "|";
    private static final Set<String> NUMBER_PARAMETERS = Set.of("page", "pageSize");
    private static final Pattern RESULT_TYPE = Pattern.compile("[A-Za-z_]{1,40}");
    private static final Logger log = LoggerFactory.getLogger(ToolAuditAspect.class);

    private final LoggingClient client;

    /**
     * Creates the aspect.
     *
     * @param client the client events are sent through
     */
    public ToolAuditAspect(LoggingClient client) {
        this.client = client;
    }

    /**
     * Runs the tool call inside a request ID scope and audits its outcome.
     *
     * @param joinPoint the tool method call
     * @param audit the annotation on the method
     * @return whatever the tool returns
     * @throws Throwable whatever the tool throws, unchanged
     */
    @Around("@annotation(audit) && within(edu.harvard.hms.dbmi.avillach.mcp.tool..*)")
    public Object audit(ProceedingJoinPoint joinPoint, AuditEvent audit) throws Throwable {
        CallerHeaders headers = CallerHeaders.from(transportContext(joinPoint.getArgs()));
        try (CallerHeaders.MdcScope ignored = headers.bindRequestId()) {
            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable failure) {
                emit(joinPoint, audit, headers, failure);
                throw failure;
            }
            emit(joinPoint, audit, headers, null);
            return result;
        }
    }

    private void emit(ProceedingJoinPoint joinPoint, AuditEvent audit, CallerHeaders headers, Throwable failure) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("outcome", failure == null ? "success" : "failure");
            putIfPresent(metadata, "user_id", clean(headers.userId()));
            metadata.putAll(argumentFields(joinPoint));
            LoggingEvent.Builder event = LoggingEvent.builder(audit.type()).action(audit.action()).metadata(metadata)
                .request(RequestInfo.builder().requestId(headers.requestId()).build());
            if (failure != null) {
                event.error(Map.of("error_type", failure instanceof ToolFailure ? "tool_failure" : "internal"));
            }
            client.send(event.build(), null, headers.requestId());
        } catch (RuntimeException e) {
            log.warn("Audit event for {} was not sent: {}", audit.action(), e.getClass().getName());
        }
    }

    private static Map<String, Object> argumentFields(ProceedingJoinPoint joinPoint) {
        Map<String, Object> fields = new LinkedHashMap<>();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        for (int i = 0; names != null && i < args.length; i++) {
            Object value = args[i];
            if (TEXT_PARAMETERS.contains(names[i]) && value instanceof String text) {
                fields.put(snake(names[i]), clean(text));
            } else if (LIST_PARAMETERS.contains(names[i]) && value instanceof List<?> list) {
                fields.put(snake(names[i]), clean(joined(list)));
            } else if (NUMBER_PARAMETERS.contains(names[i]) && value instanceof Integer number) {
                fields.put(snake(names[i]), number);
            } else if (value instanceof Map<?, ?> arguments) {
                putIfPresent(fields, "result_type", resultType(joinPoint.getTarget(), arguments));
            }
        }
        return fields;
    }

    private static String joined(List<?> list) {
        return list.stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.joining(LIST_SEPARATOR));
    }

    private static String resultType(Object target, Map<?, ?> arguments) {
        if (target instanceof CountTool) {
            return "COUNT";
        }
        Object declared = arguments.get("resultType");
        return declared instanceof String type && RESULT_TYPE.matcher(type).matches() ? type : null;
    }

    private static McpTransportContext transportContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof McpTransportContext context) {
                return context;
            }
        }
        return null;
    }

    private static String snake(String name) {
        return name.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String printable = value.replaceAll("\\p{Cntrl}", " ");
        return printable.length() > MAX_FIELD_LENGTH ? printable.substring(0, MAX_FIELD_LENGTH) : printable;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
