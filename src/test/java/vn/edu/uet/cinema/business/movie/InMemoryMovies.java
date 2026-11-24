package vn.edu.uet.cinema.business.movie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;

/** In-memory {@link MovieRepository} for business tests. */
public class InMemoryMovies implements MovieRepository {

	private final Map<UUID, Movie> rows = new LinkedHashMap<>();

	@Override
	public List<Movie> findAll() {
		return new ArrayList<>(rows.values());
	}

	@Override
	public List<Movie> findPage(int page, int size) {
		return rows.values()
				.stream()
				.sorted(Comparator.comparing(Movie::id))
				.skip((long) page * size)
				.limit(size)
				.toList();
	}

	@Override
	public long count() {
		return rows.size();
	}

	@Override
	public Optional<Movie> findById(UUID id) {
		return Optional.ofNullable(rows.get(id));
	}

	@Override
	public Movie save(Movie movie) {
		rows.put(movie.id(), movie);
		return movie;
	}

	@Override
	public void deleteById(UUID id) {
		rows.remove(id);
	}
}
