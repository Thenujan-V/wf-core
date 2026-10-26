package lk.auradot.wms.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lk.auradot.wms.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    record Body(@NotBlank String name) { }

    @RestController
    static class ProbeController {
        @GetMapping("/api-error")
        void apiError() {
            throw ApiException.conflict("DOC_CHECKED_OUT", "Document is checked out");
        }

        @GetMapping("/api-error-details")
        void apiErrorDetails() {
            throw ApiException.badRequest("WF_DEFINITION_INVALID", "The process has 1 problem(s)")
                    .with("errors", java.util.List.of(java.util.Map.of("code", "NO_END")));
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody Body body) { }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internals");
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/typed")
        void typed(@RequestParam int n) { }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void rendersApiExceptionAsProblemDetail() throws Exception {
        mvc.perform(get("/api-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"))
                .andExpect(jsonPath("$.detail").value("Document is checked out"));
    }

    @Test
    void rendersBeanValidationErrorsWithFieldList() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void hidesInternalsOfUnexpectedErrors() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andExpect(content().string(not(containsString("secret internals"))));
    }

    @Test
    void mapsAccessDeniedTo403() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void mapsMalformedParametersTo400() throws Exception {
        mvc.perform(get("/typed").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void keepsSpringClientErrorStatusesInsteadOf500() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.TEXT_PLAIN).content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void includesCorrelationIdFromMdc() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-12345678");

        mvc.perform(get("/api-error"))
                .andExpect(jsonPath("$.correlationId").value("corr-12345678"));
    }

    @Test
    void includesExtraApiExceptionProperties() throws Exception {
        mvc.perform(get("/api-error-details"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WF_DEFINITION_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_END"));
    }
}
