package com.payorch.infra.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import com.payorch.infra.logging.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a correlation ID for every request and puts it in MDC, so each
 * log line written while handling the request carries it.
 *
 * <p>This is what makes a single request traceable across six services before
 * distributed tracing exists. Phase 4 adds OpenTelemetry {@code traceId} and
 * {@code spanId} alongside it; this stays, because it is the identifier a
 * merchant can quote in a support ticket.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER = "X-Correlation-Id";

    /**
     * An inbound correlation ID is caller-controlled input that we are about to
     * write into every log line. Without this check, a caller can inject
     * newlines and forge log entries, or push a megabyte through the logging
     * pipeline on every request.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));

        MDC.put(LogFields.CORRELATION_ID, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear. Servlet containers reuse carrier threads, and a
            // leaked MDC entry attributes one merchant's log line to another.
            MDC.remove(LogFields.CORRELATION_ID);
        }
    }

    private static String resolve(String incoming) {
        if (incoming != null && SAFE.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        // Ahead of everything that might log, so no request is handled without
        // a correlation ID in scope.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
