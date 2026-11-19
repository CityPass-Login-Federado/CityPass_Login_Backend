package citypass.loginfederado.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    private static HttpServletRequest request(String remoteAddr, String forwardedFor) {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn(remoteAddr);
        when(http.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return http;
    }

    @Test
    void withoutTrustedProxiesAlwaysUsesRemoteAddr() {
        var resolver = new ClientIpResolver("");

        assertThat(resolver.resolve(request("1.2.3.4", "9.9.9.9"))).isEqualTo("1.2.3.4");
    }

    @Test
    void untrustedPeerCannotSpoofForwardedFor() {
        var resolver = new ClientIpResolver("10.0.0.1");

        assertThat(resolver.resolve(request("1.2.3.4", "9.9.9.9"))).isEqualTo("1.2.3.4");
    }

    @Test
    void trustedProxyHonorsFirstForwardedForValue() {
        var resolver = new ClientIpResolver("1.2.3.4");

        assertThat(resolver.resolve(request("1.2.3.4", "9.9.9.9, 8.8.8.8")))
                .isEqualTo("9.9.9.9");
    }

    @Test
    void trustedProxyWithoutHeaderFallsBackToRemoteAddr() {
        var resolver = new ClientIpResolver("1.2.3.4");

        assertThat(resolver.resolve(request("1.2.3.4", null))).isEqualTo("1.2.3.4");
    }

    @Test
    void multipleTrustedProxiesAreTrimmed() {
        var resolver = new ClientIpResolver("1.2.3.4, 5.6.7.8");

        assertThat(resolver.resolve(request("5.6.7.8", "9.9.9.9"))).isEqualTo("9.9.9.9");
    }
}
