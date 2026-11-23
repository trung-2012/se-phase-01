package vn.edu.uet.cinema.api.revocation;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.uet.cinema.business.account.TokenBlacklist;

@Hidden
@RestController
class TokenRevocationController {

	private static final Duration DEFAULT_TTL = Duration.ofHours(1);

	private final TokenBlacklist blacklist;

	TokenRevocationController(TokenBlacklist blacklist) {
		this.blacklist = blacklist;
	}

	@PostMapping("/api/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@AuthenticationPrincipal Jwt jwt) {
		if (jwt == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token is required");
		}
		Instant expiresAt = jwt.getExpiresAt() != null ? jwt.getExpiresAt() : Instant.now().plus(DEFAULT_TTL);
		blacklist.revoke(jwt.getTokenValue(), expiresAt);
	}
}