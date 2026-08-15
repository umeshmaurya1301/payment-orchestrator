package com.payorch.infra.web;

import com.payorch.infra.logging.LogFields;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("a well-formed inbound correlation ID is preserved and echoed")
    void preservesInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("a missing correlation ID is generated")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("a hostile correlation ID is replaced, not written to the logs")
    void rejectsLogInjectionAttempt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "ok\n{\"level\":\"ERROR\",\"forged\":true}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).doesNotContain("forged");
    }

    @Test
    @DisplayName("MDC is cleared after the request, even though the value was set")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(LogFields.CORRELATION_ID)).isNull();
    }
}
