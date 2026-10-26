package lk.auradot.wms.document.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;

public class S3ObjectStore implements ObjectStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStore(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public URI presignUpload(String key, long size, String contentType, Duration ttl) {
        return uri(presigner.presignPutObject(p -> p.signatureDuration(ttl).putObjectRequest(
                r -> r.bucket(bucket).key(key).contentType(contentType).contentLength(size))).url());
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<Long> size(String key) {
        try {
            return Optional.of(s3.headObject(r -> r.bucket(bucket).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return s3.getObject(r -> r.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new MissingObjectException(key);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new MissingObjectException(key);
            }
            throw e;
        }
    }

    @Override
    public void copy(String fromKey, String toKey) {
        try {
            s3.copyObject(r -> r.sourceBucket(bucket).sourceKey(fromKey).destinationBucket(bucket).destinationKey(toKey));
        } catch (NoSuchKeyException e) {
            throw new MissingObjectException(fromKey);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new MissingObjectException(fromKey);
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public URI presignDownload(String key, String fileName, String contentType, boolean inline, Duration ttl) {
        return uri(presigner.presignGetObject(p -> p.signatureDuration(ttl).getObjectRequest(r -> r.bucket(bucket).key(key)
                .responseContentDisposition(ContentDispositions.of(fileName, inline))
                .responseContentType(contentType))).url());
    }

    private static URI uri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
