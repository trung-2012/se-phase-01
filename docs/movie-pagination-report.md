# Báo cáo cải tiến hiệu năng: API Pagination

## 1. Tổng quan

**Dự án:** Cinema Booking System\
**Giai đoạn:** Software Architecture - Phase 2\
**Vấn đề:** No Pagination\
**API:** `GET /api/movies`

Mục tiêu của cải tiến là hạn chế số lượng bản ghi được truy xuất và trả về trong mỗi yêu cầu, giảm kích thước phản hồi và cải thiện hiệu năng khi số lượng phim tăng lớn.

## 2. Vấn đề trước khi cải tiến

Ở phiên bản ban đầu, API `GET /api/movies` trả về toàn bộ danh sách phim.

Luồng xử lý:

1. Controller gọi `MovieService.list()`.
2. Service gọi `MovieRepository.findAll()`.
3. JPA tải toàn bộ bản ghi phim.
4. Server chuyển đổi và trả về toàn bộ danh sách dưới dạng JSON.

Khi cơ sở dữ liệu có 10.000 phim, mỗi yêu cầu trả về 10.000 bản ghi.

Những hạn chế chính:

- Truy xuất và xử lý nhiều bản ghi không cần thiết.
- Tăng lượng dữ liệu truyền qua mạng.
- Tăng chi phí tuần tự hóa JSON.
- Tăng thời gian phản hồi khi dữ liệu lớn.
- Khó đảm bảo khả năng đáp ứng dưới tải cao.

## 3. Giải pháp được triển khai

Áp dụng phân trang tại tầng truy xuất dữ liệu bằng Spring Data JPA.

Các thay đổi chính:

- `MovieRepository`: bổ sung `findPage(page, size)` và `count()`.
- `JpaMovieRepository`: sử dụng `PageRequest` và sắp xếp theo `id` tăng dần.
- `MovieService`: bổ sung `listPage()` và `count()`.
- `MovieController`: hỗ trợ tham số `page` và `size`.
- `InMemoryMovies`: cập nhật repository giả lập cho các unit test.
- `MovieApiTest`: bổ sung kiểm thử chức năng phân trang.

Thông số API:

| Tham số | Giá trị |
|---|---|
| `page` | Mặc định 0, bắt đầu từ 0 |
| `size` | Mặc định 20 |
| `size` tối đa | 100 |
| Sắp xếp | `id` tăng dần |
| `page < 0` | HTTP 400 |
| `size < 1` hoặc `size > 100` | HTTP 400 |

Ví dụ:

`GET /api/movies?page=0&size=20`

Phản hồi vẫn giữ cấu trúc JSON array để giảm tác động đến các client hiện tại. Tuy nhiên, hành vi của API không truyền tham số đã thay đổi từ trả về toàn bộ dữ liệu sang tối đa 20 bản ghi.

Metadata được cung cấp qua HTTP headers:

- `X-Total-Count`
- `X-Total-Pages`
- `X-Page`
- `X-Page-Size`

Với 10.000 phim và kích thước trang là 20:

- Tổng số phim: 10.000
- Số phim được trả về: 20
- Tổng số trang: 500

## 4. Môi trường kiểm thử

- Java 21
- Spring Boot 4.1.1
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Docker Desktop
- k6 2.2.0
- Hệ điều hành: Windows
- Dữ liệu kiểm thử: 10.000 phim

Phiên bản Before được lấy từ commit `0fd7cb9` của nhánh `main`.

Phiên bản After chứa thay đổi phân trang trên nhánh `feature/api-pagination`.

Hai phiên bản sử dụng cùng cơ sở dữ liệu benchmark. Các ứng dụng được triển khai bằng Docker với cổng riêng.

| Phiên bản | Endpoint |
|---|---|
| Before | `http://localhost:18081/api/movies` |
| After | `http://localhost:18080/api/movies` |

Cấu hình tải mục tiêu:

- Phương pháp: constant-arrival-rate
- Tốc độ mục tiêu: 50 iterations/giây
- Thời gian phát tải: 30 giây
- Số lần lặp: 3 lần cho mỗi phiên bản
- Endpoint đo: `GET /api/movies`

Mỗi yêu cầu được đo theo thời gian phản hồi, kích thước dữ liệu và tỷ lệ lỗi HTTP.

## 5. Kết quả kiểm thử

### 5.1. Before - Không có Pagination

| Lần đo | Average | p95 | p99 | Dropped iterations |
|---|---:|---:|---:|---:|
| 1 | 8.552,67 ms | 13.450,14 ms | 14.417,94 ms | 375 |
| 2 | 7.884,50 ms | 12.090,44 ms | 12.983,09 ms | 368 |
| 3 | 8.959,09 ms | 13.863,85 ms | 14.760,12 ms | 382 |
| Trung bình | 8.465,42 ms | 13.134,81 ms | 14.053,72 ms | - |

*Lưu ý: Dấu chấm trong bảng dùng để phân tách hàng nghìn, không phải phần thập phân.*

Tổng số dropped iterations trong ba lần đo: **1.125**.

Kích thước phản hồi trung bình: **2.746.683 bytes**.

Tỷ lệ lỗi HTTP: **0%** đối với các yêu cầu đã được thực thi.

### 5.2. After - Có Pagination

| Lần đo | Average | p95 | p99 | Dropped iterations |
|---|---:|---:|---:|---:|
| 1 | 15,76 ms | 35,91 ms | 44,06 ms | 0 |
| 2 | 14,13 ms | 31,53 ms | 38,35 ms | 0 |
| 3 | 14,19 ms | 30,87 ms | 38,96 ms | 0 |
| Trung bình | 14,69 ms | 32,77 ms | 40,46 ms | 0 |

Kích thước phản hồi trung bình: **5.492 bytes**.

Tổng số yêu cầu tải hoàn tất trong ba lần đo: **4.503**.

Tỷ lệ lỗi HTTP: **0%**. Các kiểm tra response đạt **100%**.

### 5.3. So sánh Before và After

| Chỉ số | Before | After |
|---|---:|---:|
| Số phim trả về mỗi yêu cầu | 10.000 | 20 |
| Average response time | 8.465,42 ms | 14,69 ms |
| p95 response time | 13.134,81 ms | 32,77 ms |
| p99 response time | 14.053,72 ms | 40,46 ms |
| Response size | 2.746.683 bytes | 5.492 bytes |
| Dropped iterations (3 lần) | 1.125 | 0 |
| HTTP error rate | 0% | 0% |

**Mức giảm kích thước phản hồi:**

`(2.746.683 - 5.492) / 2.746.683 × 100 ≈ 99,80%`

**Tỷ số thời gian phản hồi trung bình:**

`8.465,42 / 14,69 ≈ 576 lần`

Kết quả cho thấy thời gian phản hồi quan sát được thấp hơn đáng kể sau khi áp dụng Pagination.

## 6. Phân tích kết quả

### 6.1. Giảm dữ liệu truyền tải

Before trả về toàn bộ 10.000 phim trong một phản hồi.

After chỉ trả về tối đa 20 phim theo kích thước trang mặc định.

Điều này làm giảm kích thước dữ liệu phản hồi khoảng 99,80%.

### 6.2. Cải thiện thời gian phản hồi

Thời gian phản hồi trung bình giảm mạnh trong kịch bản thử nghiệm có 10.000 phim.

Việc giới hạn số lượng bản ghi giúp giảm khối lượng dữ liệu cần truy xuất, chuyển đổi và truyền tới client.

### 6.3. Khả năng đáp ứng dưới tải

Before không duy trì được tốc độ phát tải mục tiêu, thể hiện qua 1.125 dropped iterations trong ba lần chạy.

After không ghi nhận dropped iterations và hoàn thành khoảng 1.501 yêu cầu tải cho mỗi lần chạy.

Điều này cho thấy giải pháp hoạt động ổn định hơn trong điều kiện benchmark được sử dụng.

### 6.4. Giới hạn của phép đo

Kết quả benchmark cần được diễn giải thận trọng:

1. Hai phiên bản trả về lượng dữ liệu khác nhau theo đúng mục tiêu của Pagination.
2. Before bị quá tải trong phép đo, làm phát sinh dropped iterations.
3. Thời gian phản hồi đo bằng k6 bao gồm cả quá trình truyền và nhận dữ liệu, không chỉ thời gian truy vấn cơ sở dữ liệu.
4. Các giá trị cải thiện không thể được xem là mức tăng tốc riêng của câu truy vấn SQL.
5. Môi trường kiểm thử cục bộ không đại diện hoàn toàn cho môi trường production.

Do đó, tỷ số khoảng 576 lần chỉ phản ánh sự khác biệt về thời gian phản hồi quan sát được trong kịch bản thử nghiệm này.

## 7. Kiểm thử chức năng và hồi quy

Đã bổ sung kiểm thử:

- Phân trang với `page` và `size`.
- Kiểm tra metadata trong response headers.
- Kiểm tra thứ tự ổn định theo ID.
- Kiểm tra dữ liệu không trùng lặp giữa các trang.
- Kiểm tra trang vượt quá phạm vi.
- Kiểm tra tham số không hợp lệ.

Kết quả chạy:

`.\mvnw.cmd clean verify`

**132 tests, 0 failures, 0 errors - BUILD SUCCESS.**

## 8. Kết luận

Việc áp dụng Pagination cho `GET /api/movies` đã giải quyết vấn đề truy xuất và trả về toàn bộ danh sách phim trong mỗi yêu cầu.

Trong thử nghiệm với 10.000 phim:

- Kích thước phản hồi giảm khoảng 99,80%.
- Thời gian phản hồi quan sát được giảm đáng kể.
- Không ghi nhận dropped iterations sau cải tiến.
- Toàn bộ 132 kiểm thử Java đều thành công.

Hướng phát triển tiếp theo gồm tối ưu việc tính tổng số bản ghi, đánh giá chi phí truy vấn ở các trang sâu và xem xét Pagination cho những endpoint danh sách khác.

## 9. Tài liệu và bằng chứng

**Script và cấu hình:**

- `compose.pagination-benchmark.yml`
- `loadtest/pagination-seed.sql`
- `loadtest/k6/movie-pagination-benchmark.js`

**Kết quả k6:**

- `docs/movie-pagination-evidence/pagination-before-01.json`
- `docs/movie-pagination-evidence/pagination-before-02.json`
- `docs/movie-pagination-evidence/pagination-before-03.json`
- `docs/movie-pagination-evidence/pagination-after-01.json`
- `docs/movie-pagination-evidence/pagination-after-02.json`
- `docs/movie-pagination-evidence/pagination-after-03.json`
