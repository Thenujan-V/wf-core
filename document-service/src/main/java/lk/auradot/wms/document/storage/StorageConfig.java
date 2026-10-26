package lk.auradot.wms.document.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties props) {
        var builder = S3Client.builder().region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (hasText(props.endpoint())) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        if (hasText(props.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
        }
        return builder.build();
    }

    /** Presigned URLs are for the browser, so they use the public endpoint. */
    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties props) {
        var builder = S3Presigner.builder().region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        String endpoint = hasText(props.publicEndpoint()) ? props.publicEndpoint() : props.endpoint();
        if (hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (hasText(props.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
        }
        return builder.build();
    }

    @Bean
    ObjectStore objectStore(S3Client s3, S3Presigner presigner, StorageProperties props) {
        return new S3ObjectStore(s3, presigner, props.bucket());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
