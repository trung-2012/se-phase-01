package vn.edu.uet.cinema.api.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.edu.uet.cinema.business.ratelimit.RateLimitStore;

@Component
@ConditionalOnProperty(
        name = "app.rate-limit.enabled",
        havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private final RateLimitStore store;
    private final ClientIpResolver clientIpResolver;
    private final int maxRequests;
    private final int windowSeconds;

    public ApiRateLimitFilter(
            RateLimitStore store,
            ClientIpResolver clientIpResolver,
            @Value("${app.rate-limit.max-requests:60}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") int windowSeconds) {

        if (maxRequests < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException(
                    "Rate limit configuration must be positive");
        }

        this.store = store;
        this.clientIpResolver = clientIpResolver;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // Use the actual network peer address.
        // Never trust user-supplied forwarding headers directly.
        String clientKey = clientIpResolver.resolve(request);

        long retryAfter;

        try {
            retryAfter = store.retryAfterSeconds(
                    clientKey,
                    maxRequests,
                    windowSeconds);

        } catch (DataAccessException | IllegalStateException ex) {
            log.error("Rate limiting unavailable", ex);

            writeProblem(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Service Unavailable");
            return;
        }

        if (retryAfter > 0) {
            response.setHeader(
                    "Retry-After",
                    Long.toString(retryAfter));

            writeProblem(
                    response,
                    429,
                    "Too Many Requests");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static void writeProblem(
            HttpServletResponse response,
            int status,
            String title) throws IOException {

        response.setStatus(status);
        response.setContentType(
                MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        response.getWriter().write(
                """
                {"type":"about:blank","title":"%s","status":%d}
                """.formatted(title, status).trim());
    }
}
