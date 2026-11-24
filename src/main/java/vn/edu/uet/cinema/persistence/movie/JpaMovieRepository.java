
package vn.edu.uet.cinema.persistence.movie;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieRepository;

@Repository
class JpaMovieRepository implements MovieRepository {

    private final MovieEntityRepository jpa;

    JpaMovieRepository(MovieEntityRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<Movie> findAll() {
        return jpa.findAll()
                .stream()
                .map(MovieEntity::toMovie)
                .toList();
    }

    @Override
    public List<Movie> findPage(int page, int size) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by("id").ascending()
        );

        return jpa.findAll(pageable)
                .getContent()
                .stream()
                .map(MovieEntity::toMovie)
                .toList();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public Optional<Movie> findById(UUID id) {
        return jpa.findById(id)
                .map(MovieEntity::toMovie);
    }

    @Override
    public Movie save(Movie movie) {
        return jpa.save(MovieEntity.from(movie)).toMovie();
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        try {
            jpa.deleteById(id);
            jpa.flush();
        } catch (DataIntegrityViolationException e) {
            if (hasConstraint(e, "fk_showtime_movie")) {
                throw new ConflictException("Movie has Showtimes");
            }
            throw e;
        }
    }
}
