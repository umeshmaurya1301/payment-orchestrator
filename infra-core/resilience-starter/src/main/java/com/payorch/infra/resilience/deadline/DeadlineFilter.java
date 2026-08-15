package com.payorch.infra.resilience.deadline;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the request's budget and binds it for the duration of the request.
 *
 * <h2>Trusting the inbound header</h2>
 *
 * {@code X-Deadline-Ms} is caller-controlled, and whether to believe it depends
 * entirely on who the caller is:
 *
 * <ul>
 *   <li><strong>{@code payments-edge} must not.</strong> Its callers are
 *       merchants. A merchant that sends {@code X-Deadline-Ms: 600000} would
 *       hold connections and heap for ten minutes per request, which is a
 *       denial-of-service with a single header and no authentication bypass
 *       required.</li>
 *   <li><strong>Internal services must.</strong> The whole mechanism is the
 *       remaining budget being passed down; a hop that ignored it would restart
 *       the clock and the budget would never actually shrink.</li>
 * </ul>
 *
 * Hence {@code trust-inbound-header}, false at the edge and true elsewhere. Even
 * when trusted the value is clamped to {@code maxBudgetMs}, because "internal"
 * is a statement about the current network topology rather than a guarantee.
 */
public class DeadlineFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER = "X-Deadline-Ms";

    private static final Logger log = LoggerFactory.getLogger(DeadlineFilter.class);

    private final long defaultBudgetMs;
    private final long maxBudgetMs;
    private final boolean trustInboundHeader;

    public DeadlineFilter(long defaultBudgetMs, long maxBudgetMs, boolean trustInboundHeader) {
        this.defaultBudgetMs = defaultBudgetMs;
        this.maxBudgetMs = maxBudgetMs;
        this.trustInboundHeader = trustInboundHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Deadline deadline = Deadline.of(resolveBudgetMs(request));

        // Echoed so a caller can see what was actually granted rather than what
        // it asked for - which is the difference between debugging a clamped
        // budget in seconds and in an afternoon.
        response.setHeader(HEADER, Long.toString(deadline.budgetMs()));

        try {
            Deadlines.runWith(deadline, () -> {
                filterChain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Unreachable: doFilter declares only the three types above.
            throw new IllegalStateException(e);
        }
    }

    private long resolveBudgetMs(HttpServletRequest request) {
        if (!trustInboundHeader) {
            return defaultBudgetMs;
        }
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return defaultBudgetMs;
        }
        try {
            long requested = Long.parseLong(header.trim());
            if (requested <= 0) {
                return defaultBudgetMs;
            }
            return Math.min(requested, maxBudgetMs);
        } catch (NumberFormatException e) {
            // Garbage in the header is a caller bug, not a reason to fail the
            // request. Fall back and say so once.
            log.debug("ignoring unparseable {} header", HEADER);
            return defaultBudgetMs;
        }
    }

    /**
     * Ahead of anything that might make a downstream call, and just after the
     * correlation filter so that a deadline-related log line still carries a
     * correlation id.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Actuator is polled by Docker's healthcheck and, from phase 4, by a
        // scraper. Neither should consume or be constrained by a payment budget.
        return request.getRequestURI().startsWith("/actuator");
    }
}
