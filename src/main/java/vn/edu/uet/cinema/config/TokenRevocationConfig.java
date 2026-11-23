package vn.edu.uet.cinema.config;

import java.util.UUID;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwtException;
import vn.edu.uet.cinema.business.account.TokenBlacklist;

@Configuration
public class TokenRevocationConfig implements BeanPostProcessor {

	private final ObjectProvider<TokenBlacklist> blacklistProvider;

	public TokenRevocationConfig(ObjectProvider<TokenBlacklist> blacklistProvider) {
		this.blacklistProvider = blacklistProvider;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof JwtEncoder encoder && !(bean instanceof UniqueJtiJwtEncoder)) {
			return new UniqueJtiJwtEncoder(encoder);
		}
		if (bean instanceof JwtDecoder decoder && !(bean instanceof RevocableJwtDecoder)) {
			return new RevocableJwtDecoder(decoder, blacklistProvider);
		}
		return bean;
	}

	private static final class UniqueJtiJwtEncoder implements JwtEncoder {
		private final JwtEncoder delegate;

		private UniqueJtiJwtEncoder(JwtEncoder delegate) {
			this.delegate = delegate;
		}

		@Override
		public Jwt encode(JwtEncoderParameters parameters) throws JwtEncodingException {
			JwtClaimsSet claims = parameters.getClaims();
			if (claims.getId() == null) {
				JwtClaimsSet updated = JwtClaimsSet.from(claims)
					.id(UUID.randomUUID().toString())
					.build();
				parameters = parameters.getJwsHeader() != null
					? JwtEncoderParameters.from(parameters.getJwsHeader(), updated)
					: JwtEncoderParameters.from(updated);
			}
			return delegate.encode(parameters);
		}
	}

	private static final class RevocableJwtDecoder implements JwtDecoder {
		private final JwtDecoder delegate;
		private final ObjectProvider<TokenBlacklist> blacklistProvider;

		private RevocableJwtDecoder(JwtDecoder delegate, ObjectProvider<TokenBlacklist> blacklistProvider) {
			this.delegate = delegate;
			this.blacklistProvider = blacklistProvider;
		}

		@Override
		public Jwt decode(String token) throws JwtException {
			Jwt jwt = delegate.decode(token);
			if (blacklistProvider.getObject().isRevoked(jwt.getTokenValue())) {
				throw new BadJwtException("Token has been revoked");
			}
			return jwt;
		}
	}
}