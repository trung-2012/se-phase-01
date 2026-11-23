package vn.edu.uet.cinema.persistence.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
class RedisBlacklistConfig {

	@Bean
	LettuceConnectionFactory blacklistRedisConnectionFactory(
		@Value("${REDIS_HOST:redis}") String host,
		@Value("${REDIS_PORT:6379}") int port) {
		return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
	}

	@Bean
	StringRedisTemplate blacklistRedisTemplate(LettuceConnectionFactory blacklistRedisConnectionFactory) {
		return new StringRedisTemplate(blacklistRedisConnectionFactory);
	}
}