# Phase 2 – Tối ưu Seat Layout Caching

## 1. Vấn đề ban đầu

**Issue:** Seat layouts re-read every request

Trong phiên bản ban đầu, mỗi lần truy vấn sơ đồ ghế của một suất chiếu, hệ thống phải đọc lại thông tin phòng chiếu và bố trí ghế từ PostgreSQL.

Trong khi đó, sơ đồ ghế (số hàng, số ghế, loại ghế) ít thay đổi. Việc truy vấn lặp lại dữ liệu này có thể tạo thêm tải không cần thiết lên database.

**Mục tiêu:** Giảm số lần truy vấn database khi đọc sơ đồ ghế, đồng thời giữ nguyên tính chính xác của trạng thái đặt ghế.

## 2. Giải pháp triển khai

Áp dụng **Spring Cache kết hợp Caffeine** tại tầng Persistence.

Các thay đổi chính:

- Sử dụng `@Cacheable` cho `JpaAuditoriumRepository.findById()`, với key là Auditorium ID.
- Sử dụng `@CacheEvict` khi lưu hoặc xóa Auditorium.
- Cấu hình cache tối đa 500 entries và thời gian hết hạn 10 phút.
- Sử dụng `TransactionAwareCacheManagerProxy` để đồng bộ thao tác cache với việc commit transaction.
- Không cache trạng thái đặt ghế theo thời gian thực. Thông tin ghế đang được giữ hoặc đã đặt vẫn được truy vấn riêng.

Các file chính:

- `pom.xml`
- `CacheConfig.java`
- `JpaAuditoriumRepository.java`
- `AuditoriumCachingTest.java`
- `BookingApiTest.java`

## 3. Kiểm thử chức năng

Kết quả kiểm thử:

- Xác nhận Cache Miss ở lần đọc đầu tiên.
- Xác nhận Cache Hit khi đọc lại cùng Auditorium.
- Xác nhận lần đọc thứ hai không phát sinh thêm câu lệnh SQL trong bài kiểm thử.
- Xác nhận cache bị xóa khi Auditorium bị xóa thành công.
- Xác nhận cache được giữ lại khi transaction rollback.
- Xác nhận trạng thái ghế được cập nhật sau khi khách hàng giữ ghế.

**Kết quả:** 130 tests passed, 0 failures, 0 errors. Maven BUILD SUCCESS.

## 4. Kiểm thử hiệu năng

**Môi trường:** Windows 11, Docker, PostgreSQL 17, Java 21, k6 v2.2.0.

**Kịch bản:** Gửi lặp lại yêu cầu `GET /api/showtimes/{id}/seats` cho cùng một suất chiếu có 120 ghế.

**Điều kiện:** 50 iterations/s, 30 giây mỗi lần, 3 lần chạy cho mỗi phiên bản. Sử dụng cùng một database và môi trường chạy.

| Chỉ số | Before (trung bình 3 lần) | After (trung bình 3 lần) | Thay đổi |
|---|---:|---:|---:|
| HTTP response time (avg) | 11.31 ms | 10.17 ms | Giảm 10.1% |
| HTTP response time (p95) | 25.17 ms | 21.78 ms | Giảm 13.5% |
| HTTP response time (p99) | 35.57 ms | 29.63 ms | Giảm 16.7% |
| HTTP request failure rate | 0% | 0% | Không đổi |

*Các giá trị p95 và p99 trong bảng là trung bình của các giá trị percentile trong ba lần chạy riêng biệt, không phải percentile gộp của tất cả requests.*

Dữ liệu gốc được lưu trong thư mục `docs/seat-cache-evidence/`, gồm 3 file Before và 3 file After.

## 5. Đánh giá

Việc áp dụng caching đã giúp loại bỏ các truy vấn SQL lặp lại đối với cùng một Auditorium trong bài kiểm thử.

Trong môi trường thử nghiệm, thời gian phản hồi trung bình và p95 đều giảm sau khi áp dụng caching.

Tuy nhiên, đây là kết quả của ba lần đo trong môi trường local. Mức cải thiện có thể thay đổi theo cấu hình máy, tải hệ thống và đặc điểm dữ liệu. Cần thêm thử nghiệm để đánh giá ở quy mô lớn.

**Kết luận:** Giải pháp đáp ứng mục tiêu giảm truy vấn database, duy trì tính chính xác của trạng thái ghế và cho thấy cải thiện hiệu năng trong điều kiện thử nghiệm.

## 6. Tài liệu kiểm chứng

- `loadtest/k6/seat-cache-benchmark.js`
- `docs/seat-cache-evidence/before-1.json` đến `before-3.json`
- `docs/seat-cache-evidence/after-1.json` đến `after-3.json`
- `src/test/java/vn/edu/uet/cinema/persistence/auditorium/AuditoriumCachingTest.java`