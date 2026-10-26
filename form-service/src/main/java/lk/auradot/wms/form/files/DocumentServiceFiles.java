package lk.auradot.wms.form.files;

import lk.auradot.wms.common.security.ServiceTokens;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.List;

/** {@link FileVerifier} over document-service's REST API, as the wms-form service account. */
public class DocumentServiceFiles implements FileVerifier {

    record VerifyBody(String submittedBy, List<String> documentIds) {
    }

    private final RestClient http;
    private final URI verifyUrl;
    private final ServiceTokens tokens;

    public DocumentServiceFiles(RestClient.Builder builder, DocumentClientProperties props, Clock clock) {
        this.http = builder.build();
        this.verifyUrl = UriComponentsBuilder.fromUri(props.baseUrl()).path("/staged-files/verify").build().toUri();
        this.tokens = new ServiceTokens(http, props.tokenUrl(), props.clientId(), props.clientSecret(), clock);
    }

    @Override
    public Verification verify(String submittedBy, List<String> documentIds) {
        try {
            Verification verification = http.post().uri(verifyUrl).headers(h -> h.setBearerAuth(tokens.get()))
                    .contentType(MediaType.APPLICATION_JSON).body(new VerifyBody(submittedBy, documentIds))
                    .retrieve().body(Verification.class);
            if (verification == null) {
                throw new FilesUnavailableException("document-service returned no answer", null);
            }
            return verification;
        } catch (RestClientException | IllegalStateException e) {
            throw new FilesUnavailableException("document-service could not check the files", e);
        }
    }
}
