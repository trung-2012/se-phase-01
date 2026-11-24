-- Dedicated dataset for movie pagination benchmarking.
-- Run only against the isolated cinema-pagination-perf database.

BEGIN;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM movie) <> 0 THEN
        RAISE EXCEPTION 'Movie table must be empty before benchmark seeding';
    END IF;
END
$$;

INSERT INTO movie (
    id,
    title,
    description,
    duration_minutes,
    genre,
    age_rating,
    release_date,
    poster_url
)
SELECT
    gen_random_uuid(),
    'Pagination Benchmark Movie ' || g,
    'Benchmark movie description number ' || g,
    120,
    'Drama',
    'P',
    DATE '2026-01-15',
    'https://example.com/poster/' || g || '.jpg'
FROM generate_series(1, 10000) AS g;

COMMIT;
