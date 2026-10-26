package lk.auradot.wms.identity.sync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class DirectorySourceSelectionTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KeycloakAdminProperties.class, AuraDocsProperties.class})
    static class Infra {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Infra.class, KeycloakClientConfig.class, AuraDocsClientConfig.class,
                    KeycloakDirectorySource.class, AuraDocsDirectorySource.class, DirectorySnapshotLoader.class)
            .withPropertyValues("wms.identity.keycloak.base-url=http://kc", "wms.identity.keycloak.realm=wms");

    @Test
    void keycloakIsTheDefaultSource() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DirectorySnapshotLoader.class);
            assertThat(context.getBean(DirectorySource.class)).isInstanceOf(KeycloakDirectorySource.class);
            assertThat(context).doesNotHaveBean(AuraDocsDirectoryClient.class);
        });
    }

    @Test
    void auraDocsSourceIsChosenByProperty() {
        runner.withPropertyValues("wms.identity.source=AURADOCS", "wms.identity.auradocs.base-url=http://docs")
                .run(context -> {
                    assertThat(context).hasSingleBean(DirectorySnapshotLoader.class);
                    assertThat(context.getBean(DirectorySource.class)).isInstanceOf(AuraDocsDirectorySource.class);
                    assertThat(context).hasSingleBean(AuraDocsDirectoryClient.class);
                });
    }
}
