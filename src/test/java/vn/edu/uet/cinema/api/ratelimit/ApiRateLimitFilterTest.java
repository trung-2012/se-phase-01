package vn.edu.uet.cinema.api.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vn.edu.uet.cinema.persistence.ratelimit.RedisRateLimitStore;

class ApiRateLimitFilterTest {

    @Test
    void allowsTwoRequestsAndRejectsThirdWith429() throws Exception {
        RedisRateLimitStore store = mock(RedisRateLimitStore.class);
        ClientIpResolver resolver = new ClientIpResolver("");
        ApiRateLimitFilter filter =
                new ApiRateLimitFilter(store, resolver, 2, 60);

        when(store.retryAfterSeconds("198.51.100.10", 2, 60))
                .thenReturn(0L, 0L, 18L);

        FilterChain chain = mock(FilterChain.class);

        for (int i = 1; i <= 3; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/movies");
            request.setRemoteAddr("198.51.100.10");

            MockHttpServletResponse response =
                    new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            if (i <= 2) {
                assertEquals(200, response.getStatus());
            } else {
                assertEquals(429, response.getStatus());
                assertEquals("18", response.getHeader("Retry-After"));
                assertTrue(response.getContentAsString()
                        .contains("\"status\":429"));
            }
        }

        verify(chain, times(2)).doFilter(any(), any());
        verify(store, times(3))
                .retryAfterSeconds("198.51.100.10", 2, 60);
    }

    @Test
    void returns503WhenRedisIsUnavailable() throws Exception {
        RedisRateLimitStore store = mock(RedisRateLimitStore.class);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                store, new ClientIpResolver(""), 60, 60);

        when(store.retryAfterSeconds("198.51.100.20", 60, 60))
                .thenThrow(new DataAccessResourceFailureException(
                        "Redis unavailable"));

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/movies");
        request.setRemoteAddr("198.51.100.20");

        MockHttpServletResponse response =
                new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("\"status\":503"));
        verifyNoInteractions(chain);
    }

    @Test
    void ignoresSpoofedHeadersOnDirectConnections() {
        ClientIpResolver resolver =
                new ClientIpResolver("172.30.55.10");

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/movies");
        request.setRemoteAddr("198.51.100.30");
        request.addHeader("X-Real-IP", "203.0.113.50");
        request.addHeader("X-Forwarded-For", "203.0.113.51");

        assertEquals("198.51.100.30", resolver.resolve(request));
    }

    @Test
    void acceptsValidClientIpOnlyFromTrustedProxy() {
        ClientIpResolver resolver =
                new ClientIpResolver("172.30.55.10");

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/movies");
        request.setRemoteAddr("172.30.55.10");
        request.addHeader("X-Real-IP", "198.51.100.40");

        assertEquals("198.51.100.40", resolver.resolve(request));
    }

    @Test
    void ignoresInvalidForwardedIp() {
        ClientIpResolver resolver =
                new ClientIpResolver("172.30.55.10");

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/movies");
        request.setRemoteAddr("172.30.55.10");
        request.addHeader(
                "X-Real-IP", "198.51.100.40, 203.0.113.10");

        assertEquals("172.30.55.10", resolver.resolve(request));
    }

    @Test
    void excludesHealthEndpointFromRateLimiting() throws Exception {
        RedisRateLimitStore store = mock(RedisRateLimitStore.class);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                store, new ClientIpResolver(""), 60, 60);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(store);
    }
}
