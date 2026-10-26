package lk.auradot.wms.identity.sync;

import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/** Reads the auraDOCS directory with the sync client's Keycloak token (auraDOCS trusts the same realm). */
public class AuraDocsDirectoryClient {

    private final RestClient http;
    private final String directoryPath;
    private final Supplier<String> token;

    public AuraDocsDirectoryClient(RestClient.Builder builder, AuraDocsProperties props, Supplier<String> token) {
        if (props.baseUrl() == null) {
            throw new IllegalStateException("wms.identity.auradocs.base-url is required when wms.identity.source=AURADOCS");
        }
        this.http = builder.baseUrl(props.baseUrl().toString()).build();
        this.directoryPath = props.directoryPath();
        this.token = token;
    }

    public AuraDocsDirectory fetch() {
        AuraDocsDirectory body = http.get()
                .uri(directoryPath)
                .headers(h -> h.setBearerAuth(token.get()))
                .retrieve()
                .body(AuraDocsDirectory.class);
        if (body == null) {
            throw new IllegalStateException("auraDOCS returned no directory");
        }
        return body;
    }
}
