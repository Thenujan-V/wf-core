package lk.auradot.wms.document.storage;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Development convenience: creates the bucket and lets the app's origins upload to it. Production buckets are provisioned. */
@Component
@ConditionalOnProperty(name = "wms.document.storage.create-bucket", havingValue = "true")
public class BucketBootstrap implements ApplicationRunner {

    private final S3Client s3;
    private final StorageProperties props;

    public BucketBootstrap(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            s3.headBucket(b -> b.bucket(props.bucket()));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(b -> b.bucket(props.bucket()));
        }
        if (props.corsOrigins() != null && !props.corsOrigins().isEmpty()) {
            s3.putBucketCors(b -> b.bucket(props.bucket()).corsConfiguration(c -> c.corsRules(CORSRule.builder()
                    .allowedOrigins(props.corsOrigins()).allowedMethods("PUT", "GET", "HEAD").allowedHeaders("*")
                    .exposeHeaders("ETag").maxAgeSeconds(3000).build())));
        }
    }
}
