package lk.auradot.wms.document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.HexFormat;

/** What the browser does with an upload ticket: PUT the bytes to the presigned URL. */
public final class TestFiles {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private TestFiles() {
    }

    public static int put(URI url, byte[] body, String contentType) {
        try {
            return HTTP.send(HttpRequest.newBuilder(url).header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static HttpResponse<byte[]> get(URI url) {
        try {
            return HTTP.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
