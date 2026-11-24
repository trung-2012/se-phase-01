# BÁO CÁO CẢI TIẾN KIẾN TRÚC PHẦN MỀM

## Distributed API Rate Limiting

## 1. Hiện trạng và vấn đề

### 1.1. Hiện trạng

Hệ thống đặt vé xem phim ban đầu chưa có cơ chế giới hạn tần suất gửi yêu cầu đến API (API Rate Limiting).

Một client có thể gửi liên tục nhiều yêu cầu trong một khoảng thời gian ngắn mà không bị giới hạn.

Khi số lượng yêu cầu tăng cao, các thành phần như Application Server, Database và Connection Pool có thể phải xử lý một khối lượng công việc lớn hơn mức cần thiết.

### 1.2. Các vấn đề tồn tại

- Không kiểm soát được số lượng yêu cầu từ mỗi client.
- Client có thể gửi quá nhiều yêu cầu trong thời gian ngắn.
- Tăng nguy cơ quá tải tài nguyên hệ thống.
- Tăng nguy cơ lạm dụng API và tấn công từ chối dịch vụ (DoS).
- Khó duy trì hạn mức thống nhất khi triển khai nhiều Application Server.

Nếu mỗi Application Server sử dụng một bộ đếm riêng biệt, client có thể nhận được hạn mức khác nhau tùy thuộc vào instance xử lý yêu cầu.

Do đó, hệ thống cần một cơ chế giới hạn tần suất truy cập có khả năng hoạt động trong môi trường phân tán.

## 2. Mục tiêu cải tiến

- Kiểm soát số lượng yêu cầu API từ mỗi địa chỉ IP.
- Hạn chế các yêu cầu vượt quá ngưỡng cho phép.
- Duy trì hạn mức thống nhất giữa nhiều Application Server.
- Ngăn chặn các trường hợp giả mạo IP thông qua HTTP header đã được kiểm thử.
- Duy trì khả năng xử lý các yêu cầu hợp lệ.
- Đảm bảo tuân thủ kiến trúc phân lớp của dự án.
- Đánh giá hiệu quả của giải pháp thông qua kiểm thử tự động và kiểm thử tải.

## 3. Giải pháp đề xuất

### 3.1. Distributed Rate Limiting sử dụng Redis

Giải pháp sử dụng Redis làm nơi lưu trữ bộ đếm yêu cầu tập trung.

Thay vì lưu bộ đếm riêng trên từng Application Server, tất cả các instance sử dụng chung dữ liệu Rate Limiting trong Redis.

Thông số cấu hình:

| Thông số | Giá trị |
|---|---|
| Thuật toán | Fixed Window Counter |
| Số yêu cầu tối đa | 60 requests |
| Thời gian giới hạn | 60 giây |
| Đối tượng giới hạn | Client IP |
| Phạm vi áp dụng | /api/** |
| Mã phản hồi khi vượt giới hạn | HTTP 429 |
| Mã phản hồi khi Redis gặp sự cố | HTTP 503 |
| Kho lưu trữ bộ đếm | Redis |
| Cơ chế cập nhật | Redis Lua Script |

### 3.2. Thuật toán Fixed Window Counter

Khi nhận được yêu cầu từ client, hệ thống thực hiện các bước sau:

1. Xác định địa chỉ IP của client.
2. Tạo hoặc xác định Redis key tương ứng với địa chỉ IP.
3. Tăng bộ đếm yêu cầu bằng lệnh INCR.
4. Nếu đây là yêu cầu đầu tiên, thiết lập thời gian hết hạn của key.
5. Nếu bộ đếm không vượt quá giới hạn, tiếp tục xử lý yêu cầu.
6. Nếu bộ đếm vượt quá giới hạn, trả về HTTP 429 Too Many Requests.
7. Trả về header Retry-After để thông báo thời gian cần chờ.
8. Khi Redis key hết hạn, client có thể gửi yêu cầu trong cửa sổ thời gian mới.

Các thao tác tăng bộ đếm, thiết lập thời gian hết hạn và kiểm tra giới hạn được thực hiện bằng Redis Lua Script.

Việc thực thi nguyên tử (atomic execution) giúp đảm bảo tính nhất quán khi nhiều yêu cầu được gửi đồng thời từ các Application Server khác nhau.

## 4. Thiết kế kiến trúc

### 4.1. Kiến trúc triển khai

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

Nginx phân phối yêu cầu đến hai Spring Boot instance.

Cả hai instance sử dụng chung Redis để kiểm tra và cập nhật bộ đếm Rate Limiting.

Do đó, việc chuyển đổi giữa các Application Server không làm thay đổi hạn mức của client.

### 4.2. Kiến trúc phân lớp

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

Trong quá trình triển khai, sự phụ thuộc trực tiếp từ API Layer đến Persistence Layer đã được loại bỏ.

Interface RateLimitStore được định nghĩa trong Business Layer.

RedisRateLimitStore thuộc Persistence Layer triển khai interface này.

ApiRateLimitFilter chỉ phụ thuộc vào interface RateLimitStore thay vì phụ thuộc trực tiếp vào lớp triển khai Redis.

Thiết kế này tuân thủ:

- Layered Architecture.
- Dependency Inversion Principle (DIP).
- Separation of Concerns.

Kết quả kiểm thử ArchUnit xác nhận rằng kiến trúc sau khi cải tiến không vi phạm các quy tắc phân lớp hiện có của dự án.

### 4.3. Các thành phần được triển khai

| Thành phần | Trách nhiệm |
|---|---|
| ApiRateLimitFilter | Kiểm tra hạn mức và xử lý yêu cầu HTTP |
| ClientIpResolver | Xác định địa chỉ IP đáng tin cậy của client |
| RateLimitStore | Định nghĩa interface lưu trữ bộ đếm |
| RedisRateLimitStore | Quản lý bộ đếm bằng Redis và Lua Script |
| Nginx | Phân phối yêu cầu và ghi đè forwarding headers |
| Redis | Lưu trữ bộ đếm dùng chung giữa các instance |

## 5. Bảo mật và khả năng xử lý sự cố

### 5.1. Ngăn chặn giả mạo địa chỉ IP

Client có thể tự thiết lập các HTTP header như X-Real-IP và X-Forwarded-For để khai báo địa chỉ IP không chính xác.

Nếu hệ thống tin tưởng trực tiếp các header này, client có thể thay đổi địa chỉ IP trên mỗi yêu cầu nhằm vượt qua giới hạn.

Để hạn chế vấn đề trên:

- Nginx ghi đè X-Real-IP và X-Forwarded-For bằng địa chỉ kết nối thực tế.
- ClientIpResolver kiểm tra địa chỉ của kết nối trực tiếp.
- X-Real-IP chỉ được sử dụng khi yêu cầu đến từ proxy đã được cấu hình là đáng tin cậy.
- Các giá trị IP không hợp lệ không được sử dụng để xác định client.
- Khi kết nối trực tiếp đến Application Server, các forwarding header không đáng tin cậy bị bỏ qua.

### 5.2. Xử lý yêu cầu vượt giới hạn

Khi client vượt quá hạn mức, hệ thống trả về:

- HTTP 429 Too Many Requests.
- Header Retry-After.
- Nội dung phản hồi theo định dạng application/problem+json.

Yêu cầu bị từ chối tại Rate Limiting Filter và không tiếp tục đi vào các thành phần xử lý nghiệp vụ phía sau.

### 5.3. Xử lý sự cố Redis

Giải pháp sử dụng chính sách Fail-Closed.

Khi Redis không khả dụng, hệ thống không cho phép client tiếp tục gửi yêu cầu mà không có giới hạn.

Thay vào đó, các API thuộc phạm vi Rate Limiting trả về HTTP 503 Service Unavailable.

Cách tiếp cận này duy trì nguyên tắc kiểm soát truy cập nhưng có thể làm giảm tính sẵn sàng của API trong thời gian Redis gặp sự cố.

## 6. Kiểm thử chức năng

### 6.1. Kiểm thử thủ công

| STT | Kịch bản kiểm thử | Kết quả thực tế | Trạng thái |
|---:|---|---|---|
| 1 | Gửi 60 yêu cầu trong một cửa sổ thời gian | 60 HTTP 200 | PASS |
| 2 | Gửi yêu cầu thứ 61 đến 65 | 5 HTTP 429 | PASS |
| 3 | Kiểm tra header Retry-After | 54-55 giây | PASS |
| 4 | Gửi yêu cầu qua hai Spring Boot instance | Sử dụng chung hạn mức | PASS |
| 5 | Giả mạo IP header thông qua Nginx | 60 HTTP 200 và 5 HTTP 429 | PASS |
| 6 | Giả mạo IP header khi kết nối trực tiếp | 60 HTTP 200 và 5 HTTP 429 | PASS |
| 7 | Dừng Redis trong môi trường kiểm thử | HTTP 503 | PASS |
| 8 | Khởi động lại Redis | PONG | PASS |

### 6.2. Kiểm thử tự động

| Nhóm kiểm thử | Số lượng | Kết quả |
|---|---:|---|
| ApiRateLimitFilterTest | 6 | PASS |
| RedisRateLimitStoreIntegrationTest | 2 | PASS |
| ArchitectureTest | 2 | PASS |
| Toàn bộ bộ kiểm thử hồi quy | 142 | PASS |

Redis Integration Test sử dụng Testcontainers để khởi tạo một Redis container riêng biệt.

Các kiểm thử xác nhận khả năng chia sẻ bộ đếm giữa nhiều đối tượng RedisRateLimitStore, sự độc lập giữa các client và khả năng tiếp tục gửi yêu cầu sau khi cửa sổ giới hạn hết hạn.

Kết quả Maven:

    Tests run: 142
    Failures: 0
    Errors: 0
    Skipped: 0
    BUILD SUCCESS

Tổng số 142 kiểm thử đã bao gồm các kiểm thử Rate Limiting và ArchitectureTest.

## 7. Phương pháp đánh giá hiệu năng

### 7.1. Môi trường thực nghiệm

| Thành phần | Công nghệ |
|---|---|
| Application | Spring Boot 4.1.1 |
| Ngôn ngữ lập trình | Java 21 |
| Database | PostgreSQL 17 |
| Distributed Counter | Redis 7 |
| Load Balancer | Nginx 1.27 |
| Container Platform | Docker Compose |
| Công cụ kiểm thử tải | k6 v2.2.0 |
| Application Server | 2 instances |
| Endpoint | GET /api/movies |

### 7.2. Thiết kế thực nghiệm

Hai cấu hình được sử dụng để đánh giá:

| Cấu hình | Rate Limiting |
|---|---|
| Before | RATE_LIMIT_ENABLED=false |
| After | RATE_LIMIT_ENABLED=true |

Cả hai cấu hình sử dụng cùng Docker image, cơ sở dữ liệu, Redis, Nginx và số lượng Application Server.

Before đại diện cho trạng thái không kích hoạt Rate Limiting trên cùng phiên bản ứng dụng, không phải một phiên bản mã nguồn cũ được xây dựng riêng biệt.

### 7.3. Kịch bản kiểm thử tải

| Thông số | Normal Traffic | High Traffic |
|---|---:|---:|
| Tần suất yêu cầu | 1 request/s | 20 requests/s |
| Thời gian thực nghiệm | 30 giây | 30 giây |
| Số lần lặp | 3 | 3 |
| Số lượng client IP | 1 | 1 |

Mỗi kịch bản được thực hiện ba lần cho cấu hình Before và After.

Tổng cộng có 12 lần thực nghiệm.

Trước mỗi lần thực nghiệm After, các bộ đếm Rate Limiting trong Redis được xóa để tránh ảnh hưởng từ lần thực nghiệm trước.

## 8. Kết quả thực nghiệm

### 8.1. Normal Traffic

| Cấu hình | Lần chạy | HTTP 200 | HTTP 429 | Avg (ms) | P95 (ms) |
|---|---:|---:|---:|---:|---:|
| Before | 1 | 31 | 0 | 39.09 | 56.64 |
| Before | 2 | 31 | 0 | 23.67 | 41.52 |
| Before | 3 | 31 | 0 | 23.84 | 34.32 |
| After | 1 | 30 | 0 | 40.36 | 61.15 |
| After | 2 | 31 | 0 | 60.88 | 61.12 |
| After | 3 | 30 | 0 | 31.44 | 53.04 |

#### Tổng hợp kết quả Normal Traffic

| Chỉ số | Before | After |
|---|---:|---:|
| Tổng số yêu cầu | 93 | 91 |
| HTTP 200 | 93 | 91 |
| HTTP 429 | 0 | 0 |
| Tỷ lệ yêu cầu được chấp nhận | 100% | 100% |
| Thời gian phản hồi trung bình (ms) | 28.87 | 44.23 |
| Trung bình P95 (ms) | 44.16 | 58.44 |
| Phản hồi ngoài dự kiến | 0 | 0 |
| Dropped iterations | 0 | 0 |

Kết quả cho thấy toàn bộ yêu cầu trong kịch bản Normal Traffic đều được xử lý thành công.

Không có yêu cầu nào bị từ chối do vượt hạn mức.

Sau khi kích hoạt Rate Limiting, thời gian phản hồi trung bình tăng từ 28.87 ms lên 44.23 ms.

Sự chênh lệch này có thể liên quan đến thao tác Redis, quá trình JVM warm-up và sự biến động của môi trường thực nghiệm.

Do đó, không thể quy toàn bộ mức tăng latency cho riêng cơ chế Rate Limiting.

### 8.2. High Traffic

| Cấu hình | Lần chạy | Tổng request | HTTP 200 | HTTP 429 | Avg (ms) |
|---|---:|---:|---:|---:|---:|
| Before | 1 | 600 | 600 | 0 | 22.13 |
| Before | 2 | 601 | 601 | 0 | 18.10 |
| Before | 3 | 600 | 600 | 0 | 17.08 |
| After | 1 | 601 | 60 | 541 | 13.51 |
| After | 2 | 600 | 60 | 540 | 15.14 |
| After | 3 | 601 | 60 | 541 | 12.66 |

#### Tổng hợp kết quả High Traffic

| Chỉ số | Before | After |
|---|---:|---:|
| Tổng số yêu cầu | 1801 | 1802 |
| HTTP 200 | 1801 | 180 |
| HTTP 429 | 0 | 1622 |
| Tỷ lệ yêu cầu bị từ chối | 0% | 90.01% |
| Thời gian phản hồi trung bình toàn bộ (ms) | 19.10 | 13.77 |
| Thời gian phản hồi trung bình HTTP 200 (ms) | 19.10 | 24.14 |
| Trung bình P95 của HTTP 200 (ms) | 34.20 | 49.89 |
| Phản hồi ngoài dự kiến | 0 | 0 |
| Dropped iterations | 0 | 0 |

Trong cả ba lần thực nghiệm High Traffic sau khi kích hoạt Rate Limiting, hệ thống đều chấp nhận chính xác 60 yêu cầu.

Tổng cộng có 1622 yêu cầu bị từ chối trên 1802 yêu cầu được gửi đến hệ thống.

Tỷ lệ từ chối được tính như sau:

    Rejection Rate = (1622 / 1802) x 100
                   = 90.01%

Kết quả xác nhận rằng hạn mức được thực thi nhất quán trong môi trường sử dụng nhiều Application Server.

### 8.3. Phân tích thời gian phản hồi

Thời gian phản hồi trung bình của toàn bộ yêu cầu trong kịch bản High Traffic giảm từ 19.10 ms xuống 13.77 ms.

Tuy nhiên, kết quả này bao gồm cả các yêu cầu HTTP 429 được từ chối sớm tại Rate Limiting Filter.

Khi chỉ xét các yêu cầu được xử lý thành công (HTTP 200), thời gian phản hồi trung bình tăng từ 19.10 ms lên 24.14 ms.

Vì vậy, kết quả không chứng minh rằng Rate Limiting làm tăng tốc độ xử lý các yêu cầu hợp lệ.

Giá trị chính của giải pháp là khả năng kiểm soát số lượng yêu cầu được tiếp tục xử lý bởi hệ thống.

Các giá trị latency tổng hợp là trung bình cộng của kết quả từ ba lần thực nghiệm.

Các giá trị P95 tổng hợp là trung bình của P95 trong từng lần chạy, không phải P95 được tính từ toàn bộ mẫu gộp lại.

### 8.4. Phân tích tỷ lệ lỗi

Theo cấu hình mặc định của k6, HTTP 429 được tính vào chỉ số http_req_failed.

Tuy nhiên, trong thực nghiệm này, HTTP 429 là phản hồi mong đợi khi client vượt quá hạn mức.

Do đó, cần phân biệt:

- HTTP 200: Yêu cầu được xử lý thành công.
- HTTP 429: Yêu cầu bị từ chối theo chính sách Rate Limiting.
- Unexpected Responses: Các phản hồi ngoài dự kiến.
- Dropped Iterations: Các lượt thực nghiệm không được thực hiện.

Trong toàn bộ 12 lần thực nghiệm:

- Unexpected Responses = 0.
- Dropped Iterations = 0.
- Tất cả số liệu đếm yêu cầu đều nhất quán.

## 9. Đánh giá các thuộc tính chất lượng

| Thuộc tính | Đánh giá |
|---|---|
| Security | Hạn chế lạm dụng API và các trường hợp giả mạo IP đã kiểm thử |
| Scalability | Duy trì hạn mức thống nhất giữa nhiều instance |
| Reliability | Cập nhật bộ đếm nguyên tử bằng Redis Lua Script |
| Maintainability | Tuân thủ Layered Architecture và DIP |
| Performance | Phát sinh thêm chi phí kiểm tra bộ đếm Redis |
| Availability | API phụ thuộc vào Redis khi sử dụng Fail-Closed |

Giải pháp cải thiện khả năng kiểm soát truy cập và hỗ trợ việc triển khai nhiều Application Server.

Tuy nhiên, cần cân nhắc sự đánh đổi giữa bảo mật, hiệu năng và tính sẵn sàng.

## 10. Hạn chế và hướng phát triển

### 10.1. Hạn chế

- Fixed Window Counter có thể cho phép lượng yêu cầu tăng đột biến tại ranh giới giữa hai cửa sổ thời gian.
- Các client sử dụng chung địa chỉ IP có thể phải chia sẻ cùng một hạn mức.
- Redis trở thành một thành phần phụ thuộc quan trọng của API khi Rate Limiting được kích hoạt.
- Chính sách Fail-Closed có thể làm giảm tính sẵn sàng khi Redis gặp sự cố.
- Thực nghiệm chỉ sử dụng một client IP.
- Chưa đo trực tiếp mức sử dụng CPU, RAM và tài nguyên Database.
- Chưa đánh giá khả năng chịu tải tối đa của toàn bộ hệ thống.
- Kết quả latency có thể chịu ảnh hưởng từ thứ tự chạy thực nghiệm và JVM warm-up.
- Cấu hình kích hoạt và danh sách trusted proxy cần được thiết lập phù hợp với môi trường triển khai thực tế.

### 10.2. Hướng phát triển

- Nghiên cứu thuật toán Sliding Window hoặc Token Bucket.
- Áp dụng hạn mức khác nhau cho từng nhóm API.
- Kết hợp giới hạn theo địa chỉ IP và tài khoản người dùng.
- Triển khai Redis High Availability.
- Bổ sung monitoring cho HTTP 429 và HTTP 503.
- Thực hiện kiểm thử tải với nhiều client IP.
- Đo mức sử dụng CPU, RAM và tài nguyên Database trước và sau khi cải tiến.

## 11. Minh chứng và khả năng tái lập

### 11.1. Các tệp minh chứng

- [Bảng tổng hợp kết quả benchmark](evidence/rate-limiting/benchmark-summary.csv)
- [12 tệp JSON kết quả thực nghiệm](evidence/rate-limiting/)
- [k6 Load Testing Script](../loadtest/k6/rate-limiting.js)
- [Docker Compose Benchmark Configuration](../compose.rate-limit-benchmark.yml)

### 11.2. Các bước tái lập thực nghiệm

1. Khởi động môi trường Docker Compose với project cinema-rate-limit-lab.
2. Thiết lập RATE_LIMIT_ENABLED=false để thực hiện benchmark Before.
3. Tái tạo hai Application Server và khởi động lại Nginx.
4. Kiểm tra trạng thái hoạt động của ứng dụng.
5. Thực hiện warm-up trước khi đo.
6. Chạy Normal Traffic và High Traffic bằng k6.
7. Thiết lập RATE_LIMIT_ENABLED=true để thực hiện benchmark After.
8. Tái tạo hai Application Server và khởi động lại Nginx.
9. Xóa các Redis key Rate Limiting trước mỗi lần thực nghiệm After.
10. Lặp lại mỗi kịch bản ba lần và lưu kết quả JSON.
11. Tổng hợp kết quả và kiểm tra tính nhất quán của số liệu.

### 11.3. Xác minh cấu hình triển khai mặc định

Tệp docker-compose.yml đã được cập nhật để kích hoạt Rate Limiting mặc định cho cả hai Spring Boot instance.

| Biến môi trường | Giá trị mặc định |
|---|---|
| RATE_LIMIT_ENABLED | true |
| RATE_LIMIT_MAX_REQUESTS | 60 |
| RATE_LIMIT_WINDOW_SECONDS | 60 |
| RATE_LIMIT_TRUSTED_PROXY_IPS | 172.31.55.10 |
| CINEMA_NETWORK_SUBNET | 172.31.55.0/24 |
| CINEMA_NGINX_IP | 172.31.55.10 |

Hai Application Server chỉ được khởi động sau khi Redis đạt trạng thái healthy.

Cấu hình triển khai được xác minh trong một Docker Compose project riêng biệt, sử dụng cấu hình mặc định kết hợp với một tệp override tạm thời để tránh xung đột cổng và tái sử dụng Docker image đã được kiểm thử.

Kết quả xác minh ngày 24/11/2026:

| Chỉ số | Kết quả |
|---|---|
| Nginx IP | 172.31.55.10 |
| Health Check | HTTP 200 - UP |
| Tổng số yêu cầu | 65 |
| HTTP 200 | 60 |
| HTTP 429 | 5 |
| Instance 1: HTTP 200 / HTTP 429 | 37 / 3 |
| Instance 2: HTTP 200 / HTTP 429 | 23 / 2 |
| Retry-After | 56 giây |
| Thời gian kiểm thử | 4.40 giây |
| IP Header Spoofing Test | PASS |

Kết quả xác nhận rằng hai Application Server chia sẻ cùng một hạn mức thông qua Redis. Việc thay đổi X-Real-IP và X-Forwarded-For trong các yêu cầu kiểm thử không làm vượt qua hạn mức.

Rate Limiting được kích hoạt mặc định khi sử dụng docker-compose.yml, trong khi giá trị mặc định trong application.yml vẫn là false.

Khi triển khai trên môi trường khác, cần đảm bảo subnet không bị trùng lặp và địa chỉ trusted proxy khớp với địa chỉ Nginx.

Nếu Docker network cũ đã tồn tại, có thể cần tái tạo network mà không xóa dữ liệu PostgreSQL.

Minh chứng: [Deployment verification CSV](evidence/rate-limiting/deployment-verification.csv)

## 12. Kết luận

Cải tiến Distributed API Rate Limiting đã được triển khai thành công trong hệ thống đặt vé xem phim sử dụng Spring Boot, Nginx và Redis.

Giải pháp đảm bảo hạn mức 60 yêu cầu trong 60 giây cho mỗi client IP và duy trì tính nhất quán giữa hai Application Server.

Kết quả thực nghiệm cho thấy:

- 100% yêu cầu hợp lệ trong kịch bản Normal Traffic được chấp nhận.
- 90.01% yêu cầu trong kịch bản High Traffic bị từ chối theo chính sách Rate Limiting.
- Mỗi lần thực nghiệm High Traffic sau cải tiến chỉ chấp nhận đúng 60 yêu cầu.
- Không có phản hồi ngoài dự kiến.
- Không có dropped iterations.
- Toàn bộ 142 kiểm thử tự động đều thành công.

Giải pháp cũng vượt qua các kiểm thử giả mạo HTTP header, xử lý sự cố Redis và kiểm tra kiến trúc phân lớp.

Mặc dù việc kiểm tra Redis làm phát sinh thêm chi phí xử lý và tạo ra sự phụ thuộc về tính sẵn sàng, giải pháp đã đạt được mục tiêu chính là kiểm soát các yêu cầu vượt hạn mức trong môi trường phân tán.

Kết quả thực nghiệm chứng minh hiệu quả kiểm soát yêu cầu, nhưng chưa đủ để kết luận về mức giảm CPU, RAM hoặc khả năng chịu tải tối đa của hệ thống.
