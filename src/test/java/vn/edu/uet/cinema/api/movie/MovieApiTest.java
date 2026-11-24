package vn.edu.uet.cinema.api.movie;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import java.util.ArrayList;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MovieApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void adminCanCreateListReadUpdateAndDeleteMovie() throws Exception {
		String admin = token();
		String created = mvc.perform(post("/api/movies").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(movieJson("The First Movie", "P")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("The First Movie"))
			.andExpect(jsonPath("$.durationMinutes").value(120))
			.andExpect(jsonPath("$.ageRating").value("P"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		mvc.perform(get("/api/movies"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(hasItem(id)));
		mvc.perform(get("/api/movies/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.description").value("A first description"));

		mvc.perform(put("/api/movies/" + id).header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(movieJson("The Updated Movie", "T13")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("The Updated Movie"))
			.andExpect(jsonPath("$.ageRating").value("T13"));

		mvc.perform(delete("/api/movies/" + id).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/movies/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void invalidMovieRequestsAreProblemDetails() throws Exception {
		String admin = token();
		String invalidEnum = mvc.perform(post("/api/movies").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(movieJson("Invalid age rating", "INVALID")))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andReturn().getResponse().getContentAsString();
		String enumField = JsonPath.read(invalidEnum, "$.errors[0].field");
		assertThat(enumField).isEqualTo("ageRating");
		assertThat(invalidEnum).contains("P", "K", "T13", "T16", "T18").doesNotContain("INVALID");

		mvc.perform(post("/api/movies").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(movieJson("Negative duration", "P").replace("\"durationMinutes\":120", "\"durationMinutes\":-1")))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		String badDate = movieJson("Bad date", "P").replace("2026-01-15", "not-a-date");
		String dateProblem = mvc.perform(post("/api/movies").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(badDate))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andReturn().getResponse().getContentAsString();
		String dateField = JsonPath.read(dateProblem, "$.errors[0].field");
		assertThat(dateField).isEqualTo("releaseDate");
		assertThat(dateProblem).contains("ISO-8601 date").doesNotContain("not-a-date");

		String title = "T".repeat(256);
		String genre = "G".repeat(256);
		String longFields = movieJson(title, "P").replace("\"genre\":\"Drama\"", "\"genre\":\"" + genre + "\"");
		String longProblem = mvc.perform(post("/api/movies").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(longFields))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andReturn().getResponse().getContentAsString();
		List<String> longFieldsReported = JsonPath.read(longProblem, "$.errors[*].field");
		assertThat(longFieldsReported).containsExactlyInAnyOrder("title", "genre");
		assertThat(longProblem).contains("Title is not allowed", "Genre is not allowed")
			.doesNotContain(title, genre, "255");

		String badPath = mvc.perform(get("/api/movies/not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andReturn().getResponse().getContentAsString();
		String pathField = JsonPath.read(badPath, "$.errors[0].field");
		String pathMessage = JsonPath.read(badPath, "$.errors[0].message");
		String detail = JsonPath.read(badPath, "$.detail");
		String instance = JsonPath.read(badPath, "$.instance");
		assertThat(pathField).isEqualTo("id");
		assertThat(pathMessage).isEqualTo("Must be a valid UUID.");
		assertThat(detail).isEqualTo("A request value has an invalid type.");
		assertThat(instance).isEqualTo("/api/movies/not-a-uuid");
	}

	@Test
	void movieWritesRequireAuthenticationAndAdminRole() throws Exception {
		mvc.perform(post("/api/movies").contentType(MediaType.APPLICATION_JSON)
				.content(movieJson("Unauthenticated movie", "P")))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		register("movie-customer@example.com");
		String customer = token("movie-customer@example.com", "password123");
		mvc.perform(post("/api/movies").header("Authorization", "Bearer " + customer)
				.contentType(MediaType.APPLICATION_JSON).content(movieJson("Customer movie", "P")))
			.andExpect(status().isForbidden())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

    @Test
    void moviePaginationReturnsCorrectPagesAndHeaders() throws Exception {
        String admin = token();
        List<String> createdIds = new ArrayList<>();

        String initialResponse = mvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getHeader("X-Total-Count");

        long initialCount = Long.parseLong(initialResponse);

        try {
            // Create five movies for pagination testing.
            for (int i = 0; i < 5; i++) {
                String created = mvc.perform(
                        post("/api/movies")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(movieJson("Pagination Test " + i, "P"))
                )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

                createdIds.add(JsonPath.read(created, "$.id"));
            }

            long total = initialCount + 5;
            long totalPages = (total + 1) / 2;

            List<UUID> returnedIds = new ArrayList<>();

            // Verify the first three pages.
            for (int page = 0; page < 3; page++) {
                String response = mvc.perform(
                        get("/api/movies")
                                .param("page", String.valueOf(page))
                                .param("size", "2")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize((int) Math.min(2L, total - (long) page * 2))))
                .andExpect(header().string(
                        "X-Total-Count", String.valueOf(total)))
                .andExpect(header().string(
                        "X-Total-Pages", String.valueOf(totalPages)))
                .andExpect(header().string(
                        "X-Page", String.valueOf(page)))
                .andExpect(header().string("X-Page-Size", "2"))
                .andReturn().getResponse().getContentAsString();

                List<String> ids = JsonPath.read(response, "$[*].id");

                ids.stream()
                        .map(UUID::fromString)
                        .forEach(returnedIds::add);
            }

            // No duplicate records and stable ascending order.
			assertThat(returnedIds)
			.doesNotHaveDuplicates()
			.isSortedAccordingTo(
					java.util.Comparator.comparing(UUID::toString)
			);

            // A page beyond the end must be empty.
            mvc.perform(get("/api/movies")
                            .param("page", String.valueOf(total))
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

        } finally {
            // Clean up only the movies created by this test.
            for (String id : createdIds) {
                mvc.perform(delete("/api/movies/" + id)
                                .header("Authorization", "Bearer " + admin))
                        .andExpect(status().isNoContent());
            }
        }
    }

    @Test
    void moviePaginationRejectsInvalidParameters() throws Exception {
        mvc.perform(get("/api/movies").param("page", "-1"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/movies").param("size", "0"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/movies").param("size", "101"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/movies").param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

	private String token() throws Exception {
		return token("admin@cinema.local", "admin12345");
	}

	private String token(String email, String password) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private void register(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Movie Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
	}

	private static String movieJson(String title, String ageRating) {
		return ("{\"title\":\"%s\",\"description\":\"A first description\",\"durationMinutes\":120,"
			+ "\"genre\":\"Drama\",\"ageRating\":\"%s\",\"releaseDate\":\"2026-01-15\","
			+ "\"posterUrl\":\"https://example.com/movie.jpg\"}").formatted(title, ageRating);
	}
}
