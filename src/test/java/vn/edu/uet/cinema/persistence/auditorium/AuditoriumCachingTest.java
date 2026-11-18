
package vn.edu.uet.cinema.persistence.auditorium;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditoriumCachingTest {

    @Autowired
    private CinemaService cinemas;

    @Autowired
    private AuditoriumService auditoriums;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void cachesRepeatedReadsAndEvictsAfterDeletion() {
        UUID cinemaId = cinemas.create(
                "Cache test cinema " + UUID.randomUUID(),
                "Test address"
        ).id();

        Auditorium created = auditoriums.create(
                cinemaId,
                "Cache test hall",
                2,
                2,
                List.of(),
                List.of()
        );

        UUID id = created.id();
        Cache cache = cacheManager.getCache("auditoriumById");
        assertNotNull(cache);

        // Ensure this auditorium is not cached
        cache.evict(id);
        var sqlStats = entityManagerFactory
        .unwrap(SessionFactory.class)
        .getStatistics();

        sqlStats.setStatisticsEnabled(true);
        sqlStats.clear();

        var caffeine =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>)
                        cache.getNativeCache();

        // Before first read
        var before = caffeine.stats();

        long sqlBefore = sqlStats.getPrepareStatementCount();

        // First read
        Auditorium first = auditoriums.get(id);
        var afterFirst = caffeine.stats();
        long sqlAfterFirst = sqlStats.getPrepareStatementCount();

        // Second read
        Auditorium second = auditoriums.get(id);
        var afterSecond = caffeine.stats();
        long sqlAfterSecond = sqlStats.getPrepareStatementCount();

        // Cache statistics
        assertEquals(1L, afterFirst.missCount() - before.missCount());
        assertEquals(1L, afterSecond.hitCount() - afterFirst.hitCount());
        assertSame(first, second);

        // SQL query statistics
        assertTrue(sqlAfterFirst > sqlBefore,
                "First read should execute SQL");

        assertEquals(sqlAfterFirst, sqlAfterSecond,
                "Second read should not execute additional SQL");

        // Delete: invalidate the cache entry
        auditoriums.delete(id);
        assertNull(cache.get(id));

        // Deleted auditorium must not be returned
        assertThrows(NotFoundException.class,
                () -> auditoriums.get(id));
    }

        @Test
        void rollbackShouldKeepAuditoriumCache() {
        UUID cinemaId = cinemas.create(
                "Rollback test cinema " + UUID.randomUUID(),
                "Test address"
        ).id();

        Auditorium created = auditoriums.create(
                cinemaId,
                "Rollback test hall",
                2,
                2,
                List.of(),
                List.of()
        );

        UUID id = created.id();
        Cache cache = cacheManager.getCache("auditoriumById");

        assertNotNull(cache);

        // Populate the cache
        Auditorium cached = auditoriums.get(id);
        assertNotNull(cache.get(id));

        // Delete inside a transaction, but roll it back
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
                auditoriums.delete(id);
                status.setRollbackOnly();
        });

        // Rollback must preserve the cache entry
        assertNotNull(cache.get(id));
        assertSame(cached, auditoriums.get(id));

        // Confirm the auditorium still exists in the database
        cache.evict(id);
        assertNotNull(auditoriums.get(id));
        }
}
