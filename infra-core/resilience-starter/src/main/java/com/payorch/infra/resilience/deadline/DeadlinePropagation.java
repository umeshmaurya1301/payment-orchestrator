package com.payorch.infra.resilience.deadline;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Puts the remaining budget on every outbound request.
 *
 * <p>Registered on a {@code RestClient.Builder}, so a service propagates the
 * deadline by building its clients normally and doing nothing else. The
 * alternative - remembering the header at each call site - is the kind of thing
 * that is correct on the day it is written and has one hole in it a year later,
 * and the hole is invisible because the request still works. It just works
 * without a bound.
 *
 * <p>The value written is the remainder <em>at the moment the request goes
 * out</em>, not at the moment the hop started, so time spent locally before the
 * call is already deducted.
 */
public class DeadlinePropagation implements ClientHttpRequestInterceptor {

    private final long fallbackBudgetMs;

    public DeadlinePropagation(long fallbackBudgetMs) {
        this.fallbackBudgetMs = fallbackBudgetMs;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        Deadline deadline = Deadlines.currentOrDefault(fallbackBudgetMs);
        request.getHeaders().set(DeadlineFilter.HEADER, Long.toString(deadline.remainingMs()));
        return execution.execute(request, body);
    }
}
