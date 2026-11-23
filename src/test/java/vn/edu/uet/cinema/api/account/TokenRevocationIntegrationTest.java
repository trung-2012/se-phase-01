package vn.edu.uet.cinema.api.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
class TokenRevocationIntegrationTest {

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
		.withExposedPorts(6379);

	static {
		POSTGRES.start();
		REDIS.start();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("app.jwt.secret", () -> "test-secret-key-with-at-least-32-characters-for-hs256");
		registry.add("app.admin.password", () -> "admin-test-password");
		registry.add("REDIS_HOST", REDIS::getHost);
		registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void revokedTokenIsRejectedWith401WhileNewLoginTokenRemainsValid() throws Exception {
		String email = "revoke-" + UUID.randomUUID() + "@example.com";
		String password = "correctHorseBatteryStaple";

		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"%s","fullName":"Revoke Tester"}
					""".formatted(email, password)))
			.andExpect(status().isCreated());

		String firstToken = loginAndGetToken(email, password);

		mockMvc.perform(get("/api/me")
				.header("Authorization", "Bearer " + firstToken))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/logout")
				.header("Authorization", "Bearer " + firstToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/me")
				.header("Authorization", "Bearer " + firstToken))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/auth/logout")
				.header("Authorization", "Bearer " + firstToken))
			.andExpect(status().isUnauthorized());

		String secondToken = loginAndGetToken(email, password);
		mockMvc.perform(get("/api/me")
				.header("Authorization", "Bearer " + secondToken))
			.andExpect(status().isOk());
	}

	@Test
	void logoutWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/api/auth/logout"))
			.andExpect(status().isUnauthorized());
	}

	private String loginAndGetToken(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"%s"}
					""".formatted(email, password)))
			.andExpect(status().isOk())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}