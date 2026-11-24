# SOFTWARE ARCHITECTURE IMPROVEMENT REPORT

## Distributed API Rate Limiting

## 1. Current System and Problem Statement

### 1.1. Current System

The original cinema ticket booking system lacked an API rate limiting mechanism.

A client could continuously submit requests within a short period without any request quota.

As incoming traffic increases, application servers, databases, and connection pools may experience unnecessary processing workloads.

### 1.2. Identified Problems

- No control over the number of requests submitted by each client.
- Excessive requests within short periods.
- Increased risk of resource exhaustion.
- Increased exposure to API abuse and denial-of-service attacks.
- Difficulty enforcing consistent quotas across multiple application instances.

If each application server maintains an independent counter, the effective client quota may vary depending on which instance processes the request.

Therefore, a distributed rate limiting mechanism is required.

## 2. Improvement Objectives

- Control API request frequency per client IP address.
- Reject requests exceeding the configured quota.
- Maintain consistent quotas across application instances.
- Prevent the tested HTTP header spoofing techniques.
- Preserve successful processing of legitimate requests.
- Comply with the existing layered architecture.
- Evaluate the solution through automated testing and load testing.

## 3. Proposed Solution

### 3.1. Redis-Based Distributed Rate Limiting

Redis is used as a centralized storage system for request counters.

Instead of maintaining independent counters, all application instances access the same rate limiting data in Redis.

| Parameter | Value |
|---|---|
| Algorithm | Fixed Window Counter |
| Maximum requests | 60 |
| Window duration | 60 seconds |
| Client identification | IP address |
| Scope | /api/** |
| Quota exceeded response | HTTP 429 |
| Redis unavailable response | HTTP 503 |
| Counter storage | Redis |
| Atomic execution | Redis Lua Script |

### 3.2. Fixed Window Counter Algorithm

For each incoming API request:

1. Resolve the client IP address.
2. Identify the corresponding Redis key.
3. Increment the counter using INCR.
4. Set the key expiration when the first request arrives.
5. Continue request processing if the quota has not been exceeded.
6. Return HTTP 429 Too Many Requests if the quota is exceeded.
7. Include the Retry-After response header.
8. Allow requests again when the current window expires.

The counter increment, expiration configuration, and quota verification are performed through a Redis Lua script.

Atomic execution ensures consistent counter updates when requests arrive concurrently from different application instances.

## 4. Architecture Design

### 4.1. Deployment Architecture

    Client
       |
       v
    Nginx Load Balancer
       |
       +---------------------+
       |                     |
       v                     v
    Spring Boot App 1    Spring Boot App 2
       |                     |
       +----------+----------+
                  |
                  v
              Shared Redis

Nginx distributes requests between two Spring Boot instances.

Both instances share the same Redis counter storage.

Consequently, switching between application instances does not reset or increase the client's request quota.

### 4.2. Layered Architecture

    API Layer
        ApiRateLimitFilter
               |
               v
    Business Layer
        RateLimitStore (Interface)
               ^
               |
    Persistence Layer
        RedisRateLimitStore
               |
               v
             Redis

During implementation, the direct dependency from the API Layer to the Persistence Layer was removed.

The RateLimitStore interface is defined in the Business Layer.

RedisRateLimitStore implements this interface in the Persistence Layer.

ApiRateLimitFilter depends on the business interface instead of the concrete Redis implementation.

This design follows:

- Layered Architecture.
- Dependency Inversion Principle (DIP).
- Separation of Concerns.

ArchUnit tests confirm compliance with the project's existing architectural rules.

### 4.3. Implemented Components

| Component | Responsibility |
|---|---|
| ApiRateLimitFilter | Enforce request quotas and handle HTTP responses |
| ClientIpResolver | Resolve trusted client IP addresses |
| RateLimitStore | Define the counter storage abstraction |
| RedisRateLimitStore | Manage counters through Redis and Lua |
| Nginx | Distribute requests and overwrite forwarding headers |
| Redis | Provide shared counter storage |

## 5. Security and Failure Handling

### 5.1. IP Header Spoofing Prevention

Clients may provide misleading values through X-Real-IP and X-Forwarded-For headers.

Trusting these headers without validation could allow clients to bypass IP-based request limits.

The implementation addresses this problem through the following measures:

- Nginx overwrites forwarding headers with the actual connection IP.
- ClientIpResolver checks the immediate network peer.
- X-Real-IP is accepted only from explicitly configured trusted proxies.
- Invalid forwarded IP values are rejected.
- Untrusted forwarding headers are ignored on direct application connections.

### 5.2. Handling Excessive Requests

When a client exceeds the configured quota, the system returns:

- HTTP 429 Too Many Requests.
- Retry-After response header.
- An application/problem+json response body.

Rejected requests do not proceed to downstream business processing.

### 5.3. Redis Failure Handling

The implementation uses a fail-closed policy.

When Redis is unavailable, the application does not silently permit unlimited API requests.

Instead, protected API endpoints return HTTP 503 Service Unavailable.

This preserves request admission control but reduces API availability during Redis outages.

## 6. Functional Testing

### 6.1. Manual Testing

| No. | Test scenario | Actual result | Status |
|---:|---|---|---|
| 1 | First 60 requests within one window | 60 HTTP 200 | PASS |
| 2 | Requests 61 through 65 | 5 HTTP 429 | PASS |
| 3 | Retry-After header | 54-55 seconds | PASS |
| 4 | Requests distributed across two instances | Shared quota | PASS |
| 5 | Spoofed IP headers through Nginx | 60 HTTP 200 and 5 HTTP 429 | PASS |
| 6 | Spoofed IP headers through direct connection | 60 HTTP 200 and 5 HTTP 429 | PASS |
| 7 | Redis stopped in test environment | HTTP 503 | PASS |
| 8 | Redis restarted | PONG | PASS |

### 6.2. Automated Testing

| Test suite | Number of tests | Result |
|---|---:|---|
| ApiRateLimitFilterTest | 6 | PASS |
| RedisRateLimitStoreIntegrationTest | 2 | PASS |
| ArchitectureTest | 2 | PASS |
| Full regression suite | 142 | PASS |

The Redis integration tests use Testcontainers to start an isolated Redis container.

The tests verify shared counters, independent quotas for different clients, and request acceptance after counter expiration.

Maven verification result:

    Tests run: 142
    Failures: 0
    Errors: 0
    Skipped: 0
    BUILD SUCCESS

The full regression suite includes the rate limiting and architecture tests listed above.

## 7. Performance Evaluation Methodology

### 7.1. Experimental Environment

| Component | Technology |
|---|---|
| Application framework | Spring Boot 4.1.1 |
| Programming language | Java 21 |
| Database | PostgreSQL 17 |
| Distributed counter | Redis 7 |
| Load balancer | Nginx 1.27 |
| Container platform | Docker Compose |
| Load testing tool | k6 v2.2.0 |
| Application instances | 2 |
| Endpoint | GET /api/movies |

### 7.2. Experimental Design

| Configuration | Rate Limiting |
|---|---|
| Before | RATE_LIMIT_ENABLED=false |
| After | RATE_LIMIT_ENABLED=true |

Both configurations use the same Docker image, database, Redis instance, Nginx configuration, and number of application instances.

Before represents the same application version with rate limiting disabled rather than a separately built historical source version.

### 7.3. Load Testing Scenarios

| Parameter | Normal Traffic | High Traffic |
|---|---:|---:|
| Request arrival rate | 1 request/s | 20 requests/s |
| Duration | 30 seconds | 30 seconds |
| Repetitions | 3 | 3 |
| Client IP addresses | 1 | 1 |

Each scenario is executed three times for both Before and After configurations.

A total of 12 benchmark runs are performed.

Rate limiting counters are cleared before each After run to prevent interference from previous experiments.

## 8. Experimental Results

### 8.1. Normal Traffic

| Configuration | Run | HTTP 200 | HTTP 429 | Avg (ms) | P95 (ms) |
|---|---:|---:|---:|---:|---:|
| Before | 1 | 31 | 0 | 39.09 | 56.64 |
| Before | 2 | 31 | 0 | 23.67 | 41.52 |
| Before | 3 | 31 | 0 | 23.84 | 34.32 |
| After | 1 | 30 | 0 | 40.36 | 61.15 |
| After | 2 | 31 | 0 | 60.88 | 61.12 |
| After | 3 | 30 | 0 | 31.44 | 53.04 |

#### Normal Traffic Summary

| Metric | Before | After |
|---|---:|---:|
| Total requests | 93 | 91 |
| HTTP 200 | 93 | 91 |
| HTTP 429 | 0 | 0 |
| Acceptance rate | 100% | 100% |
| Mean response time (ms) | 28.87 | 44.23 |
| Mean run-level P95 (ms) | 44.16 | 58.44 |
| Unexpected responses | 0 | 0 |
| Dropped iterations | 0 | 0 |

All requests in the Normal Traffic scenario were processed successfully.

No requests were rejected because of the configured rate limit.

After enabling rate limiting, the mean response time increased from 28.87 ms to 44.23 ms.

Redis operations, JVM warm-up, and environmental variation may contribute to this difference.

Therefore, the entire observed latency increase cannot be attributed exclusively to rate limiting.

### 8.2. High Traffic

| Configuration | Run | Total requests | HTTP 200 | HTTP 429 | Avg (ms) |
|---|---:|---:|---:|---:|---:|
| Before | 1 | 600 | 600 | 0 | 22.13 |
| Before | 2 | 601 | 601 | 0 | 18.10 |
| Before | 3 | 600 | 600 | 0 | 17.08 |
| After | 1 | 601 | 60 | 541 | 13.51 |
| After | 2 | 600 | 60 | 540 | 15.14 |
| After | 3 | 601 | 60 | 541 | 12.66 |

#### High Traffic Summary

| Metric | Before | After |
|---|---:|---:|
| Total requests | 1801 | 1802 |
| HTTP 200 | 1801 | 180 |
| HTTP 429 | 0 | 1622 |
| Rejection rate | 0% | 90.01% |
| Overall mean response time (ms) | 19.10 | 13.77 |
| Accepted mean response time (ms) | 19.10 | 24.14 |
| Accepted mean run-level P95 (ms) | 34.20 | 49.89 |
| Unexpected responses | 0 | 0 |
| Dropped iterations | 0 | 0 |

Each High Traffic After run accepted exactly 60 requests.

A total of 1622 out of 1802 incoming requests were rejected.

The rejection rate is calculated as follows:

    Rejection Rate = (1622 / 1802) x 100
                   = 90.01%

The results demonstrate consistent quota enforcement in the tested distributed deployment.

### 8.3. Response Time Analysis

The overall mean response time under High Traffic decreased from 19.10 ms to 13.77 ms.

However, this measurement includes HTTP 429 responses rejected early by the Rate Limiting Filter.

When only successful HTTP 200 responses are considered, the mean response time increased from 19.10 ms to 24.14 ms.

Therefore, the results do not demonstrate faster processing of successful API requests.

The primary benefit of the solution is controlling how many requests proceed to downstream application processing.

Reported mean latency values are arithmetic averages of the three individual benchmark runs.

Reported P95 values are averages of individual run-level percentiles, not percentiles calculated from pooled request samples.

### 8.4. Failure Rate Analysis

By default, k6 includes HTTP 429 responses in the http_req_failed metric.

However, HTTP 429 is an intentional policy response in this experiment.

The following categories must therefore be distinguished:

- HTTP 200: Successfully processed requests.
- HTTP 429: Requests intentionally rejected by rate limiting.
- Unexpected Responses: Responses outside the expected status categories.
- Dropped Iterations: Scheduled load testing iterations that were not started.

Across all 12 benchmark runs:

- Unexpected Responses = 0.
- Dropped Iterations = 0.
- All request count consistency checks passed.

## 9. Quality Attribute Evaluation

| Quality attribute | Evaluation |
|---|---|
| Security | Limits API abuse and prevents tested header spoofing attempts |
| Scalability | Maintains shared quotas across multiple instances |
| Reliability | Uses atomic Redis counter updates |
| Maintainability | Complies with layered architecture and DIP |
| Performance | Introduces additional Redis counter verification overhead |
| Availability | Depends on Redis under the fail-closed policy |

The solution improves request admission control and supports distributed application deployments.

However, architectural trade-offs exist between security, performance, and availability.

## 10. Limitations and Future Improvements

### 10.1. Limitations

- Fixed windows may permit bursts near window boundaries.
- Clients sharing an IP address also share the same quota.
- Redis becomes a critical dependency when rate limiting is enabled.
- Fail-closed behavior reduces availability during Redis outages.
- The benchmark represents a single client IP address.
- CPU, RAM, and database resource utilization were not measured.
- Maximum system capacity was not evaluated.
- Benchmark ordering and JVM warm-up may influence latency measurements.
- Deployment-specific activation and trusted proxy settings are required.

### 10.2. Future Improvements

- Evaluate Sliding Window and Token Bucket algorithms.
- Configure different quotas for different API categories.
- Combine IP-based and authenticated-account-based quotas.
- Introduce Redis High Availability.
- Monitor HTTP 429 and HTTP 503 responses.
- Perform load testing with multiple client IP addresses.
- Measure CPU, memory, and database resource consumption before and after the improvement.

## 11. Evidence and Reproducibility

### 11.1. Evidence Files

- [Benchmark summary CSV](evidence/rate-limiting/benchmark-summary.csv)
- [12 raw JSON benchmark results](evidence/rate-limiting/)
- [k6 load testing script](../loadtest/k6/rate-limiting.js)
- [Docker Compose benchmark configuration](../compose.rate-limit-benchmark.yml)

### 11.2. Reproduction Procedure

1. Start the Docker Compose environment using project cinema-rate-limit-lab.
2. Set RATE_LIMIT_ENABLED=false for the Before configuration.
3. Recreate both application instances and restart Nginx.
4. Verify application health.
5. Warm up the API before measurement.
6. Execute Normal Traffic and High Traffic scenarios using k6.
7. Set RATE_LIMIT_ENABLED=true for the After configuration.
8. Recreate both application instances and restart Nginx.
9. Clear rate limiting Redis keys before each After run.
10. Repeat each scenario three times and export JSON results.
11. Aggregate benchmark results and verify request count consistency.

### 11.3. Default Deployment Configuration Verification

The default docker-compose.yml configuration was updated to enable rate limiting for both Spring Boot instances.

| Environment variable | Default value |
|---|---|
| RATE_LIMIT_ENABLED | true |
| RATE_LIMIT_MAX_REQUESTS | 60 |
| RATE_LIMIT_WINDOW_SECONDS | 60 |
| RATE_LIMIT_TRUSTED_PROXY_IPS | 172.31.55.10 |
| CINEMA_NETWORK_SUBNET | 172.31.55.0/24 |
| CINEMA_NGINX_IP | 172.31.55.10 |

Both application instances depend on a healthy Redis service before startup.

The deployment configuration was verified in an isolated Docker Compose project using the default configuration and a temporary override file to avoid port conflicts and reuse the previously tested Docker image.

Verification results on November 24, 2026:

| Metric | Result |
|---|---|
| Nginx IP | 172.31.55.10 |
| Health Check | HTTP 200 - UP |
| Total requests | 65 |
| HTTP 200 | 60 |
| HTTP 429 | 5 |
| Instance 1: HTTP 200 / HTTP 429 | 37 / 3 |
| Instance 2: HTTP 200 / HTTP 429 | 23 / 2 |
| Retry-After | 56 seconds |
| Test duration | 4.40 seconds |
| IP Header Spoofing Test | PASS |

The results confirm that both application instances enforce a shared quota through Redis. Changing X-Real-IP and X-Forwarded-For headers in the tested requests did not bypass the rate limit.

Rate limiting is enabled by default in docker-compose.yml, while the application.yml default remains false.

Other deployment environments must ensure that the configured subnet does not overlap with existing networks and that the trusted proxy address matches the Nginx address.

Existing Docker networks may require recreation without deleting PostgreSQL data.

Evidence: [Deployment verification CSV](evidence/rate-limiting/deployment-verification.csv)

## 12. Conclusion

Distributed API Rate Limiting was successfully implemented in the cinema ticket booking system using Spring Boot, Nginx, and Redis.

The solution enforces a quota of 60 requests per 60 seconds per client IP address and maintains consistent counters across two application instances.

Experimental results demonstrate:

- 100% acceptance of requests in the Normal Traffic scenario.
- 90.01% rejection of requests in the High Traffic After scenario.
- Exactly 60 accepted requests in each High Traffic After run.
- Zero unexpected responses.
- Zero dropped iterations.
- All 142 automated tests passed.

The implementation also passed the tested HTTP header spoofing scenarios, Redis failure handling tests, and layered architecture verification.

Although Redis verification introduces additional processing overhead and availability dependencies, the solution achieves its primary objective of distributed request admission control.

The results demonstrate effective quota enforcement, but they do not establish CPU or memory savings, faster successful-request processing, or maximum system capacity.
