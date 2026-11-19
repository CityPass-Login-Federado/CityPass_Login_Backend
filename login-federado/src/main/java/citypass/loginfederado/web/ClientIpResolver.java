package citypass.loginfederado.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Resuelve la IP real del cliente para rate-limit, lockout y features de
 * anomalía.
 *
 * X-Forwarded-For es una cabecera que el CLIENTE puede enviar: si se confía
 * en ella sin más, cualquiera puede falsificar su IP y evadir el rate-limit,
 * el lockout por usuario o envenenar el detector de anomalías. Por eso solo
 * se usa cuando el par inmediato (getRemoteAddr) es un proxy confiable
 * declarado en `app.trusted-proxies`; en cualquier otro caso se devuelve la
 * IP de la conexión.
 *
 * La lista es de IPs exactas (separadas por coma). Si está vacía (default),
 * NUNCA se confía en X-Forwarded-For: se usa siempre la IP del socket.
 */
@Component
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<String> trustedProxies;

    public ClientIpResolver(@Value("${app.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(proxy -> !proxy.isEmpty())
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }

        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return remoteAddr != null && trustedProxies.contains(remoteAddr);
    }
}
