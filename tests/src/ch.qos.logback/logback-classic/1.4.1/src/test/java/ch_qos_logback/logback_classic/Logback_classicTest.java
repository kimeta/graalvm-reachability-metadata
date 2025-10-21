/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package ch_qos_logback.logback_classic;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Logback_classicTest {

    @Test
    void testLogger() throws JoranException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        context.reset();

        String config = """
                <configuration>
                  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder>
                      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
                    </encoder>
                  </appender>

                  <root level="INFO">
                    <appender-ref ref="STDOUT" />
                  </root>
                </configuration>
                """;

        configurator.doConfigure(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);

        Logger logger = context.getLogger(Logback_classicTest.class);
        logger.info("Test log message");

        assertThat(logger.getEffectiveLevel().toString()).isEqualTo("INFO");
        assertThat(logger.isEnabledFor(ch.qos.logback.classic.Level.INFO)).isTrue();
    }

    @Test
    void testLoggerContext() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertThat(context.getLoggerList()).isNotEmpty();
    }
}
