package lk.auradot.wms.workflow.job;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;

/** Keeps process-defined webhooks away from internal services and cloud metadata endpoints (SSRF). */
public final class UrlGuard {

    private UrlGuard() {
    }

    /** Why the URL must not be called, or empty when it may be. */
    public static Optional<String> blocked(URI uri, boolean allowPrivateNetworks, HostResolver resolver)
            throws UnknownHostException {
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            return Optional.of("only http(s) webhooks are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.of("the webhook URL has no host");
        }
        if (allowPrivateNetworks) {
            return Optional.empty();
        }
        for (InetAddress address : resolver.resolve(uri.getHost())) {
            if (isInternal(address)) {
                return Optional.of("'" + uri.getHost() + "' points to a private or internal network address");
            }
        }
        return Optional.empty();
    }

    private static boolean isInternal(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || (address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC);
    }
}
