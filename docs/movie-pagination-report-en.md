# Performance Improvement Report: API Pagination

**Project:** Cinema Booking System\
**Course:** Software Architecture - Phase 2\
**Improvement:** No Pagination\
**Target Endpoint:** `GET /api/movies`

## 1. Executive Summary

This improvement addresses the lack of pagination in the movie catalogue API.

Previously, `GET /api/movies` retrieved and returned every movie stored in the database. This approach increased database processing, JSON serialization overhead, response size, and network transfer costs as the catalogue grew.

The improved implementation uses database-level pagination with Spring Data JPA.

Under a benchmark workload containing 10,000 movies, the paginated endpoint returned 20 movies per request by default.

Key observed results:

- Response size decreased by approximately 99.80%.
- Average end-to-end response time decreased substantially.
- The paginated version sustained the target request rate without reported dropped iterations.
- All 132 Java tests passed.

The response-time difference must be interpreted in the context of the tested workload. It is not a measurement of database query speed alone.

## 2. Problem Statement

### 2.1. Original Implementation

The original API used the following request flow:

1. `MovieController` called `MovieService.list()`.
2. `MovieService` called `MovieRepository.findAll()`.
3. The JPA repository retrieved every movie.
4. The application converted all records into response objects.
5. The API returned the complete list as a JSON array.

For a database containing 10,000 movies, a single request returned all 10,000 records.

### 2.2. Architectural Issues

This design introduced several scalability concerns:

- Unnecessary database reads for clients that only needed a small subset.
- Increased memory usage during response processing.
- Higher JSON serialization and deserialization costs.
- Larger HTTP response payloads.
- Longer response times under heavy workloads.
- Reduced ability to sustain the target request rate.

The main issue was the absence of a bounded data retrieval mechanism.

## 3. Proposed Solution and Implementation

### 3.1. Database-Level Pagination

Pagination was implemented across the existing layered architecture.

| Layer | Main Changes |
|---|---|
| API | Added `page` and `size` query parameters |
| Business Service | Added `listPage()` and `count()` |
| Repository Interface | Added `findPage()` and `count()` |
| JPA Persistence | Used `PageRequest` and ID-based sorting |
| Test Repository | Added in-memory pagination support |
| API Tests | Added pagination and parameter validation tests |

The implementation uses Spring Data JPA to retrieve only the requested page from the database.

Movies are ordered by ID in ascending order to provide deterministic pagination.

### 3.2. API Contract

**Endpoint:**

`GET /api/movies?page=0&size=20`

| Parameter | Behavior |
|---|---|
| `page` | Zero-based page index |
| Default `page` | 0 |
| `size` | Maximum number of movies per page |
| Default `size` | 20 |
| Maximum `size` | 100 |
| Negative `page` | HTTP 400 |
| `size` outside 1-100 | HTTP 400 |

The response body remains a JSON array.

Pagination metadata is returned through HTTP response headers:

- `X-Total-Count`
- `X-Total-Pages`
- `X-Page`
- `X-Page-Size`

For 10,000 movies with a page size of 20:

| Metric | Value |
|---|---:|
| Total movies | 10,000 |
| Movies returned | 20 |
| Total pages | 500 |
| Current page | 0 |

### 3.3. Compatibility Considerations

Although the response body retains its JSON array format, the default endpoint behavior has changed.

Previously, calling `/api/movies` returned every movie.

After the improvement, the same request returns the first 20 movies.

Therefore, existing clients that expect the complete catalogue may require updates.

## 4. Benchmark Environment

### 4.1. Technology Stack

- Java 21
- Spring Boot 4.1.1
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Docker Desktop
- k6 2.2.0
- Windows development environment

### 4.2. Compared Versions

| Version | Description |
|---|---|
| Before | Original `main` commit `0fd7cb9` |
| After | `feature/api-pagination` implementation |

Both versions used the same benchmark PostgreSQL database containing 10,000 movies.

The tests targeted the application endpoints directly, without the Nginx load balancer.

| Version | Endpoint |
|---|---|
| Before | `http://localhost:18081/api/movies` |
| After | `http://localhost:18080/api/movies` |

The After application was stopped before the Before load tests to reduce competition between the two application processes.

### 4.3. Load Configuration

| Setting | Value |
|---|---|
| Tool | k6 |
| Executor | constant-arrival-rate |
| Target arrival rate | 50 iterations/second |
| Load duration | 30 seconds |
| Repetitions | 3 per version |
| Dataset | 10,000 movies |
| Request | `GET /api/movies` |
| Expected Before response | 10,000 movies |
| Expected After response | 20 movies |

The benchmark recorded average response time, p95, p99, HTTP error rate, response size, and dropped iterations.

## 5. Performance Results

### 5.1. Before: No Pagination

All response times below are expressed in milliseconds.

| Run | Average | p95 | p99 | Dropped Iterations |
|---|---:|---:|---:|---:|
| 1 | 8,552.67 | 13,450.14 | 14,417.94 | 375 |
| 2 | 7,884.50 | 12,090.44 | 12,983.09 | 368 |
| 3 | 8,959.09 | 13,863.85 | 14,760.12 | 382 |
| Mean | 8,465.42 | 13,134.81 | 14,053.72 | - |

Additional observations:

- Response size: 2,746,683 bytes.
- Completed measured requests: 3,376 across three runs.
- Total dropped iterations: 1,125.
- HTTP error rate: 0% for executed requests.

The baseline did not sustain the configured arrival rate.

### 5.2. After: Database-Level Pagination

All response times below are expressed in milliseconds.

| Run | Average | p95 | p99 | Dropped Iterations |
|---|---:|---:|---:|---:|
| 1 | 15.76 | 35.91 | 44.06 | 0 |
| 2 | 14.13 | 31.53 | 38.35 | 0 |
| 3 | 14.19 | 30.87 | 38.96 | 0 |
| Mean | 14.69 | 32.77 | 40.46 | 0 |

Additional observations:

- Response size: 5,492 bytes.
- Completed measured requests: 4,503 across three runs.
- Total reported dropped iterations: 0.
- HTTP error rate: 0%.
- Response checks passed: 100%.

### 5.3. Before vs. After

| Metric | Before | After |
|---|---:|---:|
| Movies returned per request | 10,000 | 20 |
| Mean response time | 8,465.42 ms | 14.69 ms |
| Mean p95 response time | 13,134.81 ms | 32.77 ms |
| Mean p99 response time | 14,053.72 ms | 40.46 ms |
| Response size | 2,746,683 B | 5,492 B |
| Dropped iterations, three runs | 1,125 | 0 |
| HTTP error rate | 0% | 0% |

**Response size reduction:**

`(2,746,683 - 5,492) / 2,746,683 x 100 = approximately 99.80%`

**Observed average response-time ratio:**

`8,465.42 / 14.69 = approximately 576x`

The approximately 576x difference describes observed end-to-end response time under the specified benchmark conditions.

It must not be interpreted as a 576x improvement in database query performance.

## 6. Performance Analysis

### 6.1. Reduced Response Payload

Before pagination, each request returned 10,000 movie records.

After pagination, the default endpoint returned only 20 records.

This reduced the response payload by approximately 99.80%.

### 6.2. Reduced End-to-End Latency

The paginated version achieved substantially lower observed HTTP response times.

Possible contributing factors include:

- Fewer database records retrieved.
- Fewer domain objects converted to response objects.
- Less JSON serialization.
- Less data transmitted over HTTP.
- Reduced client-side response receiving overhead.

The benchmark does not isolate the individual contribution of each factor.

### 6.3. Load Handling

The original implementation recorded 1,125 dropped iterations across three runs.

The paginated implementation recorded no dropped iterations in the three measured runs.

In k6, a dropped iteration indicates an iteration that could not be started according to the configured schedule.

This can reflect pressure on the load generator as well as the system under test, so it should not be treated as direct proof of server-only capacity.

### 6.4. Response Receiving Overhead

The original version transferred approximately 2.75 MB per request.

In the baseline measurements, a substantial share of total HTTP request duration was spent receiving the response.

This indicates that response transfer and client-side handling were important contributors to the observed latency.

## 7. Functional and Regression Testing

Additional API tests were introduced to verify:

- Pagination using `page` and `size`.
- Pagination response headers.
- Stable ordering by movie ID.
- No duplicate records across pages.
- Empty responses for out-of-range pages.
- HTTP 400 responses for invalid parameters.

The entire Java test suite was executed with:

`.\mvnw.cmd clean verify`

**Final result: 132 tests, 0 failures, 0 errors - BUILD SUCCESS.**

This confirms that the implemented pagination tests and existing regression tests passed in the tested environment.

## 8. Limitations and Future Improvements

### 8.1. Benchmark Limitations

The performance results should be interpreted with the following limitations:

1. Before and After intentionally return different response sizes.
2. The baseline experienced dropped iterations under the target workload.
3. k6 response time includes network transfer and response receiving.
4. The benchmark does not isolate database execution time.
5. Local Docker results may differ from production performance.
6. The measurements use three runs per version, not a broader statistical sample.

Therefore, the reported improvement represents the observed behavior of this end-to-end API scenario.

### 8.2. Future Improvements

Potential next steps include:

- Avoiding redundant count queries during pagination.
- Evaluating performance for high page offsets.
- Considering keyset pagination for large datasets.
- Adding pagination to other list endpoints where appropriate.
- Reviewing client compatibility with the changed default response behavior.
- Measuring database execution time separately from HTTP transfer time.

## 9. Conclusion

The pagination improvement successfully addressed the unbounded movie catalogue retrieval issue.

With 10,000 movies in the benchmark database:

- The response payload decreased by approximately 99.80%.
- Observed average response time decreased substantially.
- The paginated version completed the configured workload without reported dropped iterations.
- All 132 Java tests passed.

These results demonstrate the practical value of database-level pagination for reducing unnecessary data retrieval and response payloads.

## 10. Source Code and Evidence

**Implementation and benchmark files:**

- `compose.pagination-benchmark.yml`
- `loadtest/pagination-seed.sql`
- `loadtest/k6/movie-pagination-benchmark.js`

**Before k6 results:**

- `docs/movie-pagination-evidence/pagination-before-01.json`
- `docs/movie-pagination-evidence/pagination-before-02.json`
- `docs/movie-pagination-evidence/pagination-before-03.json`

**After k6 results:**

- `docs/movie-pagination-evidence/pagination-after-01.json`
- `docs/movie-pagination-evidence/pagination-after-02.json`
- `docs/movie-pagination-evidence/pagination-after-03.json`

**Vietnamese report:** `docs/movie-pagination-report.md`
