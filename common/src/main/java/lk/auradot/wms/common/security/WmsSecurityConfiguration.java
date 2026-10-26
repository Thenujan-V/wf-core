package lk.auradot.wms.common.security;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class WmsSecurityConfiguration {

    static final String AUDIENCES_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";

    @Bean
    SecurityFilterChain wmsSecurityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        List<?> audiences = Binder.get(environment).bind(AUDIENCES_PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of());
        if (audiences.isEmpty()) {
            // Without it any token issued by the realm (e.g. to another client) would be accepted.
            throw new IllegalStateException(AUDIENCES_PROPERTY + " must be set (e.g. wms-api) for every WMS service");
        }
        var jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
