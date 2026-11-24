
package vn.edu.uet.cinema.business.movie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MovieRepository {

    List<Movie> findAll();

    // Fetch only the requested page from the database.
    List<Movie> findPage(int page, int size);

    // Count all movies for pagination metadata.
    long count();

    Optional<Movie> findById(UUID id);

    Movie save(Movie movie);

    void deleteById(UUID id);
}
