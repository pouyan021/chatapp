package com.kutumlabs.chatapp.api;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiErrorsTelemetryTests {
    @Test
    void unexpectedFailureIsLoggedOnceAndResponseContainsNoExceptionDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiErrors.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new ApiErrors()
                    .unavailable(new IllegalStateException("database unavailable"), new MockHttpServletRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().code()).isEqualTo("TEMPORARILY_UNAVAILABLE");
            assertThat(response.getBody().message()).doesNotContain("database unavailable");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
