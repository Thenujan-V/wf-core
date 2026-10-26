package lk.auradot.wms.document;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** RabbitMQ for the tests that need a broker; import it next to @DocumentIntegrationTest. */
@TestConfiguration(proxyBeanMethods = false)
public class TestRabbit {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return RABBIT;
    }
}
