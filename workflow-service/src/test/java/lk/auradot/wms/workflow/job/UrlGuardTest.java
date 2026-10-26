package lk.auradot.wms.workflow.job;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class UrlGuardTest {

    private static HostResolver resolvesTo(String ip) {
        return host -> new InetAddress[] {InetAddress.getByName(ip)};
    }

    @Test
    void blocksInternalAddresses() throws UnknownHostException {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254",
                "0.0.0.0", "::1", "fd00::1", "224.0.0.1"}) {
            assertThat(UrlGuard.blocked(URI.create("https://hooks.example.com/x"), false, resolvesTo(ip)))
                    .as(ip).isPresent();
        }
    }

    @Test
    void allowsPublicAddressesAndHttpOnly() throws UnknownHostException {
        assertThat(UrlGuard.blocked(URI.create("https://hooks.example.com/x"), false, resolvesTo("93.184.216.34"))).isEmpty();
        assertThat(UrlGuard.blocked(URI.create("ftp://hooks.example.com/x"), false, resolvesTo("93.184.216.34"))).isPresent();
        assertThat(UrlGuard.blocked(URI.create("https:///nohost"), false, resolvesTo("93.184.216.34"))).isPresent();
    }

    @Test
    void privateNetworksCanBeAllowedForDevelopment() throws UnknownHostException {
        assertThat(UrlGuard.blocked(URI.create("http://127.0.0.1:9000/x"), true, resolvesTo("127.0.0.1"))).isEmpty();
    }
}
