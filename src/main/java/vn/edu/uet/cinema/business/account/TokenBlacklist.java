package vn.edu.uet.cinema.business.account;

import java.time.Instant;

public interface TokenBlacklist {

	void revoke(String tokenValue, Instant expiresAt);

	boolean isRevoked(String tokenValue);
}