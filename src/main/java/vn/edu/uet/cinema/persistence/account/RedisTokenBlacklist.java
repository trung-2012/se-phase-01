package vn.edu.uet.cinema.persistence.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import vn.edu.uet.cinema.business.account.TokenBlacklist;

@Component
class RedisTokenBlacklist implements TokenBlacklist {

	private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklist.class);
	private static final String KEY_PREFIX = "revoked:token:";

	private final StringRedisTemplate redis;
	private final Map<String, Instant> localFallback = new ConcurrentHashMap<>();

	RedisTokenBlacklist(StringRedisTemplate blacklistRedisTemplate) {
		this.redis = blacklistRedisTemplate;
	}

	@Override
	public void revoke(String tokenValue, Instant expiresAt) {
		Duration ttl = Duration.between(Instant.now(), expiresAt);
		if (ttl.isNegative() || ttl.isZero()) {
			return;
		}
		String key = KEY_PREFIX + sha256Hex(tokenValue);
		try {
			redis.opsForValue().set(key, "1", ttl);
		} catch (DataAccessException ex) {
			log.warn("Redis unavailable during token revocation; falling back to local memory", ex);
			localFallback.put(key, expiresAt);
		}
	}

	@Override
	public boolean isRevoked(String tokenValue) {
		String key = KEY_PREFIX + sha256Hex(tokenValue);
		try {
			return Boolean.TRUE.equals(redis.hasKey(key));
		} catch (DataAccessException ex) {
			log.warn("Redis unavailable during token blacklist check; checking local fallback", ex);
			Instant expiry = localFallback.get(key);
			if (expiry == null) {
				return false;
			}
			if (Instant.now().isAfter(expiry)) {
				localFallback.remove(key);
				return false;
			}
			return true;
		}
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}
}