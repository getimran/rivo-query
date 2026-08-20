package com.knowimran.rivoquery.interfaceapi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private static final String SECRET = "postgresql://admin:do-not-log@database/private";

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsValidBoundaryValuesAndMakesTheIdAvailableDuringTheRequest() throws Exception {
        assertAccepted("a");
        assertAccepted("a" + "-".repeat(63));
    }

    @Test
    void replacesMissingInvalidAndMultipleValuesWithAValidGeneratedId() throws Exception {
        assertReplaced(null);
        assertReplaced(" invalid");
        assertReplaced("a".repeat(65));

        MockHttpServletRequest request = request();
        request.addHeader(RequestIdFilter.HEADER_NAME, "first");
        request.addHeader(RequestIdFilter.HEADER_NAME, "second");
        assertGenerated(request, "first");
    }

    @Test
    void logsOnlySanitizedCompletionMetadataAndAlwaysClearsMdc() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MockHttpServletRequest request = request();
            request.addHeader("Authorization", "Bearer " + SECRET);
            request.setContent(SECRET.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank();
                ((MockHttpServletResponse) wrappedResponse).setStatus(503);
            });

            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("request completed");
            assertThat(event.getKeyValuePairs().toString())
                    .contains("httpStatus=\"503\"", "outcome=\"server_error\"");
            assertThat(event.toString()).doesNotContain(SECRET, "Authorization", "SQLException");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertAccepted(String requestId) throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(RequestIdFilter.HEADER_NAME, requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> wrappedHeader = new AtomicReference<>();

        filter.doFilter(request, response, (wrappedRequest, ignoredResponse) -> {
            wrappedHeader.set(((jakarta.servlet.http.HttpServletRequest) wrappedRequest)
                    .getHeader(RequestIdFilter.HEADER_NAME));
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo(requestId);
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(requestId);
        assertThat(wrappedHeader).hasValue(requestId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private void assertReplaced(String supplied) throws Exception {
        MockHttpServletRequest request = request();
        if (supplied != null) {
            request.addHeader(RequestIdFilter.HEADER_NAME, supplied);
        }
        assertGenerated(request, supplied);
    }

    private void assertGenerated(MockHttpServletRequest request, String supplied) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> wrappedHeader = new AtomicReference<>();

        filter.doFilter(request, response, (wrappedRequest, ignoredResponse) -> wrappedHeader.set(
                ((jakarta.servlet.http.HttpServletRequest) wrappedRequest)
                        .getHeader(RequestIdFilter.HEADER_NAME)));

        String generated = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(generated)
                .isNotEqualTo(supplied)
                .matches(RequestIdFilter.VALID_REQUEST_ID);
        assertThat(wrappedHeader).hasValue(generated);
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/health/live");
    }
}
