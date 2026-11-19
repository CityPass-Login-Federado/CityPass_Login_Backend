package citypass.loginfederado.metrics;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RawAuthenticationEventTest {

    @Test
    void loginShouldCreateLoginEvent() {
        RawAuthenticationEvent event = RawAuthenticationEvent.login(
                "U000042",
                "nicolas",
                "reclamos",
                "citypass-web",
                "192.168.1.10",
                "Mozilla/5.0"
        );

        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());

        assertEquals("identidad.login", event.eventType());
        assertEquals("U000042", event.userSub());
        assertEquals("nicolas", event.username());
        assertEquals("reclamos", event.department());
        assertEquals("citypass-web", event.clientId());
        assertEquals("192.168.1.10", event.ipAddress());
        assertEquals("Mozilla/5.0", event.userAgent());

        assertNull(event.chainId());
    }

    @Test
    void refreshShouldCreateRefreshEvent() {
        UUID chainId = UUID.randomUUID();

        RawAuthenticationEvent event = RawAuthenticationEvent.refresh(
                "U000042",
                "nicolas",
                "reclamos",
                "citypass-web",
                chainId,
                "192.168.1.10",
                "Mozilla/5.0"
        );

        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());

        assertEquals("identidad.refresh", event.eventType());
        assertEquals("U000042", event.userSub());
        assertEquals("nicolas", event.username());
        assertEquals("reclamos", event.department());
        assertEquals("citypass-web", event.clientId());
        assertEquals(chainId, event.chainId());
        assertEquals("192.168.1.10", event.ipAddress());
        assertEquals("Mozilla/5.0", event.userAgent());
    }

    @Test
    void logoutShouldCreateLogoutEvent() {
        UUID chainId = UUID.randomUUID();

        RawAuthenticationEvent event = RawAuthenticationEvent.logout(
                "U000042",
                "reclamos",
                "citypass-web",
                chainId,
                "192.168.1.10",
                "Mozilla/5.0"
        );

        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());

        assertEquals("identidad.logout", event.eventType());
        assertEquals("U000042", event.userSub());
        assertEquals("reclamos", event.department());
        assertEquals("citypass-web", event.clientId());
        assertEquals(chainId, event.chainId());
        assertEquals("192.168.1.10", event.ipAddress());
        assertEquals("Mozilla/5.0", event.userAgent());

        assertNull(event.username());
    }

    @Test
    void eventsShouldHaveUniqueIds() {
        RawAuthenticationEvent first = RawAuthenticationEvent.login(
                "U000042",
                "nicolas",
                "reclamos",
                "citypass-web",
                "192.168.1.10",
                "Mozilla/5.0"
        );

        RawAuthenticationEvent second = RawAuthenticationEvent.login(
                "U000042",
                "nicolas",
                "reclamos",
                "citypass-web",
                "192.168.1.10",
                "Mozilla/5.0"
        );

        assertNotEquals(first.eventId(), second.eventId());
    }
}