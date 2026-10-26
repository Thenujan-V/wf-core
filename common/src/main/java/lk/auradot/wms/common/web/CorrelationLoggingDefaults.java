package lk.auradot.wms.common.web;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Makes every log line carry the request's correlation id (put in the MDC by {@link CorrelationIdFilter}),
 * so the id shown in an error response can be found in the logs. Added last, so a service can override it.
 */
public class CorrelationLoggingDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY = "logging.pattern.correlation";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource("wmsCorrelationLoggingDefaults",
                Map.of(PROPERTY, "[%X{" + CorrelationIdFilter.MDC_KEY + ":-}] ")));
    }
}
