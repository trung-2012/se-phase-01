package vn.edu.uet.cinema.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private final Set<String> trustedProxyIps;

    public ClientIpResolver(
            @Value("${app.rate-limit.trusted-proxy-ips:}")
            String trustedProxyIps) {

        this.trustedProxyIps = Arrays.stream(
                        trustedProxyIps.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {

        String remoteIp = request.getRemoteAddr();

        // Do not trust forwarding headers from unknown peers.
        if (!trustedProxyIps.contains(remoteIp)) {
            return remoteIp;
        }

        // Nginx overwrites this header with its observed client IP.
        String forwardedIp = request.getHeader("X-Real-IP");

        if (!isValidIpLiteral(forwardedIp)) {
            return remoteIp;
        }

        return forwardedIp;
    }

    private static boolean isValidIpLiteral(String value) {

        if (value == null || value.isBlank()) {
            return false;
        }

        // Reject lists, hostnames, whitespace and injected values.
        if (value.length() > 45
                || !value.matches("[0-9a-fA-F:.]+")
                || (!value.contains(".") && !value.contains(":"))) {
            return false;
        }

        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
