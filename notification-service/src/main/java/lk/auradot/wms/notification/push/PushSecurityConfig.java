package lk.auradot.wms.notification.push;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class PushSecurityConfig {

    /**
     * The WebSocket handshake carries no token (a browser cannot add one), so this one path is open; the STOMP
     * CONNECT that follows must carry the token (StompAuthInterceptor). Every other path keeps the shared rules.
     */
    @Bean
    @Order(1)
    SecurityFilterChain pushHandshake(HttpSecurity http) throws Exception {
        http.securityMatcher(PushSocketConfig.ENDPOINT)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
