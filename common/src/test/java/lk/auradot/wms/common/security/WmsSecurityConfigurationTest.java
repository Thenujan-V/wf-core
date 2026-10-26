package lk.auradot.wms.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CommonTestApplication.class,
        properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test",
                "spring.security.oauth2.resourceserver.jwt.audiences=wms-api"})
@AutoConfigureMockMvc
class WmsSecurityConfigurationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mvc.perform(get("/probe/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsJwtAndUsesSubjectAsName() throws Exception {
        mvc.perform(get("/probe/me").with(jwt().jwt(j -> j.subject("user-1"))
                        .authorities(new KeycloakRealmRoleConverter())))
                .andExpect(status().isOk())
                .andExpect(content().string("user-1"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void enforcesRealmRolesWithProblemDetail() throws Exception {
        mvc.perform(get("/probe/admin").with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("user"))))
                        .authorities(new KeycloakRealmRoleConverter())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/probe/admin").with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("wms-admin"))))
                        .authorities(new KeycloakRealmRoleConverter())))
                .andExpect(status().isOk());
    }
}
