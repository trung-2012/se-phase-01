package vn.edu.uet.cinema.persistence.ratelimit;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import vn.edu.uet.cinema.business.ratelimit.RateLimitStore;

@Component
public class RedisRateLimitStore implements RateLimitStore {

    private static final String KEY_PREFIX = "ratelimit:api:";

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>();

    static {
        SCRIPT.setScriptText("""
                local hits = redis.call('INCR', KEYS[1])

                if hits == 1 then
                    redis.call('EXPIRE', KEYS[1], ARGV[1])
                end

                if hits > tonumber(ARGV[2]) then
                    local remaining = redis.call('TTL', KEYS[1])

                    if remaining < 1 then
                        return 1
                    end

                    return remaining
                end

                return 0
                """);

        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Returns 0 when the request is allowed.
     * Returns the retry delay in seconds when the limit is exceeded.
     */
    @Override
    public long retryAfterSeconds(
            String clientKey,
            int maxRequests,
            int windowSeconds) {

        if (maxRequests < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException(
                    "Rate limit and window must be positive");
        }

        Long result = redis.execute(
                SCRIPT,
                List.of(KEY_PREFIX + clientKey),
                Integer.toString(windowSeconds),
                Integer.toString(maxRequests));

        if (result == null) {
            throw new IllegalStateException(
                    "Redis rate limiting returned no result");
        }

        return result;
    }
}
