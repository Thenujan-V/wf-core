package lk.auradot.wms.document.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Blank endpoint and keys mean AWS S3 with the default credentials chain (spec §2.3: one bucket per customer). */
@ConfigurationProperties("wms.document.storage")
public record StorageProperties(String bucket, String region, String endpoint, String publicEndpoint, String accessKey,
                                String secretKey, boolean pathStyle, boolean createBucket, List<String> corsOrigins,
                                Duration uploadExpiry, Duration downloadExpiry, long maxUploadSize) {
}
