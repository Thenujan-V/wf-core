package lk.auradot.wms.document;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same identity as deploy/seaweedfs/s3.json. */
    static final String S3_CONFIG = """
            {"identities":[{"name":"wms","credentials":[{"accessKey":"wms","secretKey":"wms-dev-secret"}],
             "actions":["Admin","Read","Write","List","Tagging"]}]}""";

    /** One container of each kind for every test context in this module. */
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @SuppressWarnings("resource")
    static final GenericContainer<?> S3 = new GenericContainer<>("chrislusf/seaweedfs:4.47")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
            .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
            .withExposedPorts(8333)
            // Anonymous requests get 403 once the S3 gateway is up.
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403).withStartupTimeout(Duration.ofMinutes(2)));

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return MYSQL;
    }

    @Bean
    GenericContainer<?> seaweedfsContainer() {
        return S3;
    }

    @Bean
    DynamicPropertyRegistrar storageEndpoint(GenericContainer<?> seaweedfsContainer) {
        return registry -> {
            registry.add("wms.document.storage.endpoint",
                    () -> "http://" + seaweedfsContainer.getHost() + ":" + seaweedfsContainer.getMappedPort(8333));
            registry.add("wms.document.storage.public-endpoint",
                    () -> "http://" + seaweedfsContainer.getHost() + ":" + seaweedfsContainer.getMappedPort(8333));
        };
    }
}
