package lk.auradot.wms.audit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MySQL with the same two users as deploy/mysql/init/05-audit.sql: the service connects as wms_audit (SELECT, INSERT
 * only) and Flyway migrates as an administrator.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** One container for every test context in this module. */
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withUsername("root");

    static {
        MYSQL.start();
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS 'wms_audit'@'%' IDENTIFIED BY 'wms_audit'");
            statement.execute("GRANT SELECT, INSERT ON " + MYSQL.getDatabaseName() + ".* TO 'wms_audit'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar auditDatabase() {
        return registry -> {
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", () -> "wms_audit");
            registry.add("spring.datasource.password", () -> "wms_audit");
            registry.add("spring.flyway.user", () -> "root");
            registry.add("spring.flyway.password", MYSQL::getPassword);
        };
    }

    @Bean
    AuditAdminDb auditAdminDb() {
        return new AuditAdminDb(JdbcClient.create(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())));
    }
}
