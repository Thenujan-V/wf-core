package lk.auradot.wms.document.storage;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.TestFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DocumentIntegrationTest
class S3ObjectStoreTest {

    @Autowired ObjectStore store;
    @Autowired S3Client s3;
    @Autowired StorageProperties props;

    @Test
    void theBrowserUploadsThroughAPresignedUrlForExactlyTheAnnouncedSize() throws Exception {
        String key = "objects/" + UUID.randomUUID();
        byte[] body = "quarterly report".getBytes(StandardCharsets.UTF_8);

        assertThat(TestFiles.put(store.presignUpload(key, body.length + 1, "text/plain", Duration.ofMinutes(5)), body, "text/plain"))
                .isEqualTo(403);
        assertThat(store.size(key)).isEmpty();

        assertThat(TestFiles.put(store.presignUpload(key, body.length, "text/plain", Duration.ofMinutes(5)), body, "text/plain"))
                .isEqualTo(200);
        assertThat(store.size(key)).contains((long) body.length);
        try (InputStream in = store.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
    }

    @Test
    void downloadsCarryTheFileNameAndDeleteRemovesTheObject() {
        String key = "objects/" + UUID.randomUUID();
        byte[] body = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        TestFiles.put(store.presignUpload(key, body.length, "application/pdf", Duration.ofMinutes(5)), body, "application/pdf");

        var response = TestFiles.get(store.presignDownload(key, "Plan.pdf", "application/pdf", true, Duration.ofMinutes(5)));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-disposition")).hasValueSatisfying(v -> assertThat(v).startsWith("inline;"));

        store.delete(key);
        store.delete(key);
        assertThat(store.size(key)).isEmpty();
        assertThatThrownBy(() -> store.open(key)).isInstanceOf(ObjectStore.MissingObjectException.class);
    }

    @Test
    void theBucketExistsAndAcceptsBrowserUploadsFromTheApp() {
        var cors = s3.getBucketCors(b -> b.bucket(props.bucket()));

        assertThat(cors.corsRules()).anySatisfy(rule -> {
            assertThat(rule.allowedOrigins()).contains("http://localhost:4200");
            assertThat(rule.allowedMethods()).contains("PUT", "GET");
        });
    }
}
