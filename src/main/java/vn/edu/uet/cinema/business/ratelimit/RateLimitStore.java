package vn.edu.uet.cinema.business.ratelimit;

public interface RateLimitStore {

    /**
     * Returns 0 if allowed, or retry delay in seconds if rejected.
     */
    long retryAfterSeconds(
            String clientKey,
            int maxRequests,
            int windowSeconds);
}
