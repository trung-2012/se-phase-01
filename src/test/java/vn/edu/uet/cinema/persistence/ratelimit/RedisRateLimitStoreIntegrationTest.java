package vn.edu.uet.cinema.persistence.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisRateLimitStoreIntegrationTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUp() {
        REDIS.start();

        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(
                        REDIS.getHost(),
                        REDIS.getMappedPort(6379));

        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }

    @Test
    void multipleInstancesShareTheSameRedisLimit() {
        RedisRateLimitStore app1 =
                new RedisRateLimitStore(redisTemplate);

        RedisRateLimitStore app2 =
                new RedisRateLimitStore(redisTemplate);

        String client = "shared-" + UUID.randomUUID();

        assertEquals(0L, app1.retryAfterSeconds(client, 3, 60));
        assertEquals(0L, app2.retryAfterSeconds(client, 3, 60));
        assertEquals(0L, app1.retryAfterSeconds(client, 3, 60));

        long retryAfter =
                app2.retryAfterSeconds(client, 3, 60);

        assertTrue(retryAfter >= 1 && retryAfter <= 60,
                "Fourth request must be rate limited");

        String anotherClient = "other-" + UUID.randomUUID();

        assertEquals(0L,
                app1.retryAfterSeconds(anotherClient, 3, 60));
    }

    @Test
    void requestsAreAllowedAgainAfterWindowExpires()
            throws InterruptedException {

        RedisRateLimitStore store =
                new RedisRateLimitStore(redisTemplate);

        String client = "expiry-" + UUID.randomUUID();

        assertEquals(0L,
                store.retryAfterSeconds(client, 1, 1));

        assertTrue(
                store.retryAfterSeconds(client, 1, 1) > 0,
                "Second request must be blocked");

        Thread.sleep(1500);

        assertEquals(0L,
                store.retryAfterSeconds(client, 1, 1),
                "Request must be allowed after expiration");
    }
}
