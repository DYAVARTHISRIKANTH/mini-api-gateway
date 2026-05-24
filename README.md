# Mini API Gateway with Sliding Window Rate Limiter

This project is a production-quality, high-performance **Mini API Gateway** implemented using Java 17 and Spring Boot 3. It serves as an entry point for API traffic management, showcasing sliding window rate limiting, HTTP 429 error handling, servlet filters, correlation ID tracing, and centralized structured logging using SLF4J and Logback.

---

## 📖 Table of Contents
1. [Architecture & Flow Diagrams](#-architecture--flow-diagrams)
2. [Why Sliding Window Rate Limiting?](#-why-sliding-window-rate-limiting)
3. [Algorithmic Complexity & Concurrency](#-algorithmic-complexity--concurrency)
4. [Centralized Structured Logging](#-centralized-structured-logging)
5. [Security & Reliability Features](#-security--reliability-features)
6. [Setup and Execution Instructions](#-setup-and-execution-instructions)
7. [API Documentation & curl Commands](#-api-documentation--curl-commands)
8. [Sample Logs](#-sample-logs)
9. [Interview Q&A, Scaling & Future Evolution](#-interview-qa-scaling--future-evolution)

---

## 🏗️ Architecture & Flow Diagrams

The Gateway operates at the Servlet Filter layer (highest precedence), allowing it to intercept traffic before it hits any Spring `@RestController` or downstream microservice. This ensures low-latency rejection of malicious/throttled traffic.

### Request Flow Diagram (ASCII)

```text
       [ Client Request ]
               │
               ▼
   ┌───────────────────────┐
   │  RateLimitingFilter   │ <─── 1. Generate Correlation ID & put in MDC
   └───────────┬───────────┘
               │
               ├─► 2. Extract client IP (safe from X-Forwarded-For)
               ├─► 3. Call RateLimiter.isAllowed(ip)
               │
               ▼
     [ Limit Exceeded? ]
          /        \
        YES         NO
        /             \
       ▼               ▼
┌──────────────┐     ┌──────────────────────┐
│  Return 429  │     │  Forward to Chain    │
│ Too Many Req │     │ (GatewayController)  │
│ (Retry-After)│     └──────────┬───────────┘
└──────────────┘                │
                                ▼
                     ┌──────────────────────┐
                     │    Generate Response │
                     │  (Logs elapsed time) │
                     └──────────────────────┘
```

---

## ⏱️ Why Sliding Window Rate Limiting?

A **Sliding Window** rate limiting algorithm is significantly superior to a **Fixed Window** algorithm because it solves the "boundary burst" vulnerability.

### Comparison: Sliding vs. Fixed Window

*   **Fixed Window Vulnerability:** Suppose a rate limit is defined as `10 requests per 60 seconds`. In a fixed window starting at `00:00` and ending at `01:00`, a malicious client can make 10 requests at `00:59` and another 10 requests at `01:01`. The fixed window allows this because both groups of 10 fall in separate windows. However, the system experiences a burst of **20 requests in 2 seconds**, defeating the intended rate-limiting traffic constraint.
*   **Sliding Window Solution:** Instead of static boundaries, a sliding window evaluates a dynamic, rolling time window (e.g., current time minus 60 seconds). Every request timestamp is recorded. When a new request arrives, any timestamps older than the trailing 60-second mark are purged, and the remaining count is validated. This guarantees that at no point will there be more than `N` requests in *any* continuous 60-second window.

---

## ⚙️ Algorithmic Complexity & Concurrency

### Time & Space Complexity
*   **Time Complexity:** 
    *   `isAllowed(ip)`: **O(K)** in the worst case, where $K$ is the number of requests in the current window (at most the configured `limit`). The polling of expired timestamps takes time proportional to the expired elements. In the average case, with steady traffic, this is **O(1)**.
    *   Map lookup is **O(1)** via `ConcurrentHashMap`.
*   **Space Complexity:** **O(N * K)**, where $N$ is the number of active client IPs and $K$ is the average number of request timestamps per client. To prevent memory leaks, a background scheduler periodically purges idle entries (IPs with empty deques), reducing space complexity to **O(M * K)** where $M$ is the number of *actively* requesting client IPs.

### Concurrency Considerations
*   **Fine-Grained Locking:** Instead of locking the entire `ConcurrentHashMap` or a global rate limiter object, this implementation uses a monitor lock per IP (by synchronizing on the specific `ConcurrentLinkedDeque` instance). Requests from `Client A` do not block requests from `Client B`, maintaining high throughput under massive concurrent loads.
*   **Double-Ended Queue:** We use `ConcurrentLinkedDeque` to allow lock-free queue operations where possible. The compound check-and-add (read-modify-write) is protected under the per-IP synchronized block to ensure absolute accuracy of sliding window size.

---

## 📝 Centralized Structured Logging

Logging is integrated using **SLF4J + Logback**. The logging system adheres to production patterns:
1.  **Correlation ID (MDC):** When a request arrives, a unique UUID (`correlationId`) is generated and pushed to the Mapped Diagnostic Context (MDC). This ID is prepended to every log statement executed on that request thread (even down to database/service layers) and returned as a response header (`X-Correlation-Id`).
2.  **MDC Clean Up:** The filter guarantees that the MDC is cleaned up in a `finally` block. This is critical in container/servlet architectures using thread pools (e.g., Tomcat), preventing correlation ID leakage across concurrent/subsequent requests on recycled threads.
3.  **Appenders:**
    *   `CONSOLE`: Formatted for high readability and parsing.
    *   `ROLLING FILE`: Logs to `logs/gateway.log` with size-and-time-based rolling (`gateway-YYYY-MM-DD.i.log`).

---

## 🔒 Security & Reliability Features

1.  **Proxy-Safe IP Extraction:** API gateways are usually deployed behind Load Balancers (ALB, Nginx, Cloudflare). `IpExtractor` parses standard headers like `X-Forwarded-For` (leftmost IP) and `X-Real-IP`, ensuring the rate limiter blocks the actual client instead of the load balancer.
2.  **Log Injection Protection:** Inputs (such as correlation IDs, request methods, and paths) are sanitized using `Sanitizer.java` to strip carriage return/line feed characters, neutralizing CWE-117 log injection exploits.
3.  **Fail-Safe Defaults:** If rate limiter configs are missing, the gateway defaults to standard configurations, allowing traffic rather than creating an outage, but logging warning metrics.

---

## 🚀 Setup and Execution Instructions

### Prerequisites
*   Java 17 or higher
*   Maven 3.6+

### Build and Test
Run unit and integration tests (including the concurrency and cleanup validation):
```bash
mvn clean test
```

### Run the Application
Start the gateway application:
```bash
mvn spring-boot:run
```
The gateway starts on port `8080`.

---

## 📡 API Documentation & curl Commands

### Exposed Endpoints
*   `GET /api/test` - Verification endpoint.
*   `GET /api/health` - Basic health status.
*   `GET /api/status` - Live uptime and system information.

### Configuration
By default (configured in `application.yml`), the limit is set to **10 requests per 60 seconds**.

### Testing the Rate Limiter (curl)

**1. Standard Request:**
```bash
curl -i http://localhost:8080/api/test
```
*Expected Response (200 OK):*
```http
HTTP/1.1 200 OK
X-Correlation-Id: a5b38d38-2c21-4f1e-9273-df1818d8e578
Content-Type: application/json

{"status":"success","message":"Gateway integration test successful.","timestamp":"2026-05-24T16:25:00.123Z"}
```

**2. Triggering HTTP 429 Too Many Requests:**
You can simulate rate limits by making 11 quick requests:
```bash
for ($i=1; $i -le 11; $i++) { curl -i http://localhost:8080/api/test }
```
*Expected Response on the 11th Request (429 Too Many Requests):*
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-Correlation-Id: e14283db-1191-4cfd-b4b1-bbfa8db56bb7
Content-Type: application/json

{
  "timestamp": "2026-05-24T16:25:12.456Z",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please try again later.",
  "path": "/api/test"
}
```

---

## 📋 Sample Logs

Logs captured in `logs/gateway.log` showing standard execution, warning rate limit breaches, and correlation mapping:

```text
2026-05-24 21:55:00.101 [http-nio-8080-exec-1] INFO  com.example.gateway.filter.RateLimitingFilter - [CorrelationID: 64f2fbde-b4f0-464a-bd5b-f2835ad0e241] - Incoming request: GET /api/test | Client IP: 192.168.1.12
2026-05-24 21:55:00.104 [http-nio-8080-exec-1] INFO  com.example.gateway.controller.GatewayController - [CorrelationID: 64f2fbde-b4f0-464a-bd5b-f2835ad0e241] - Processing /api/test request inside GatewayController.
2026-05-24 21:55:00.108 [http-nio-8080-exec-1] INFO  com.example.gateway.filter.RateLimitingFilter - [CorrelationID: 64f2fbde-b4f0-464a-bd5b-f2835ad0e241] - Request completed: GET /api/test | Status: 200 | Latency: 7 ms

2026-05-24 21:55:12.450 [http-nio-8080-exec-11] INFO  com.example.gateway.filter.RateLimitingFilter - [CorrelationID: e14283db-1191-4cfd-b4b1-bbfa8db56bb7] - Incoming request: GET /api/test | Client IP: 192.168.1.12
2026-05-24 21:55:12.451 [http-nio-8080-exec-11] WARN  com.example.gateway.limiter.SlidingWindowRateLimiter - [CorrelationID: e14283db-1191-4cfd-b4b1-bbfa8db56bb7] - Rate limit exceeded for client IP: 192.168.1.12. Current requests in window: 10
2026-05-24 21:55:12.452 [http-nio-8080-exec-11] WARN  com.example.gateway.filter.RateLimitingFilter - [CorrelationID: e14283db-1191-4cfd-b4b1-bbfa8db56bb7] - Rate limit exceeded for client IP: 192.168.1.12 | Path: /api/test | Retry-After: 48s

2026-05-24 21:55:30.000 [scheduling-1] INFO  com.example.gateway.limiter.SlidingWindowRateLimiter - [CorrelationID: N/A] - Background rate limiter cleanup complete. Removed 1 idle client IP entries. Active IPs: 0
```

---

## 🧠 Interview Q&A, Scaling & Future Evolution

### 💬 Common Interview Questions & Answers
1.  **Q: How does Sliding Window differ from Token Bucket?**
    *   *A:* Sliding Window is time-precise, keeping exact timestamps of requests to prevent boundary bursts. Token Bucket (and Leaky Bucket) uses tokens generated at a fixed fill rate. Token Bucket allows for configured "burstiness" (accumulating tokens up to capacity), while Sliding Window strictly enforces the maximum limit in the trailing duration.
2.  **Q: Why use ConcurrentHashMap over standard HashMap?**
    *   *A:* `ConcurrentHashMap` uses lock striping or CAS (Compare-And-Swap) operations internally, allowing high-concurrency read/write operations without locking the entire table. A standard `HashMap` is not thread-safe and can cause infinite loops or data corruption under concurrent updates.
3.  **Q: How do you handle clock drift in Sliding Window?**
    *   *A:* Under single-node execution, the system JVM clock (`System.currentTimeMillis()`) is uniform. However, in a distributed network, clock drift can cause discrepancies. Using NTP (Network Time Protocol) to sync clocks, or relying on centralized timestamp generation (like Redis/Lua scripts) is standard practice.

### 🌐 Distributed Rate Limiting & Redis Scaling Strategy
*   **The Problem:** In-memory maps (`ConcurrentHashMap`) store limits per node. If a gateway is scaled to 5 pods behind a load balancer, a client's requests will be round-robined. A client could potentially make $5 \times \text{limit}$ requests.
*   **The Solution:** Outsource the window state to **Redis** using a sorted set (`ZSET`).
    *   **Algorithm via Redis:**
        1.  Create a Redis key for the client IP (e.g. `rate:192.168.1.1`).
        2.  Run a transactional multi-exec pipeline or a Lua script (for atomicity).
        3.  Remove elements in the `ZSET` whose scores are less than `currentTime - windowSeconds`. Use `ZREMRANGEBYSCORE`.
        4.  Count the number of elements in the `ZSET` using `ZCARD`.
        5.  If `ZCARD < limit`:
            *   Add the current timestamp as both value and score using `ZADD`.
            *   Set an expiration on the key using `EXPIRE` (e.g., matching the window size) so Redis cleans up idle keys automatically.
            *   Return `true` (Allowed).
        6.  If `ZCARD >= limit`, return `false` (Blocked).

### 🐳 Kubernetes & Docker Deployment Ideas
*   **Dockerization:** Build a lightweight distroless Docker image or multi-stage build:
    ```dockerfile
    FROM maven:3.9-eclipse-temurin-17 AS build
    WORKDIR /app
    COPY pom.xml .
    COPY src ./src
    RUN mvn clean package -DskipTests

    FROM eclipse-temurin:17-jre-alpine
    WORKDIR /app
    COPY --from=build /app/target/gateway-1.0.0-SNAPSHOT.jar gateway.jar
    EXPOSE 8080
    ENTRYPOINT ["java", "-jar", "gateway.jar"]
    ```
*   **Kubernetes Configuration:** Deploy the Gateway as a Kubernetes `Deployment` with a `Service` or an `Ingress`. Define resource limits (`cpu` and `memory`) and configure Horizontal Pod Autoscaling (HPA) based on CPU/memory usage or custom Prometheus request rates.

### 🗺️ API Gateway Evolution Roadmap
1.  **Phase 1 (Current):** Basic Servlet Filter global rate limiting, IP extraction, logging.
2.  **Phase 2:** Integrate Redis-backed distributed rate limiting and OAuth2/JWT token verification.
3.  **Phase 3:** Transition to reactive programming using **Spring Cloud Gateway** (built on Project Reactor and Netty) to handle non-blocking asynchronous requests for sub-millisecond gateway routing latency.
4.  **Phase 4:** Add dynamic configuration reloading (via Spring Cloud Config or Consul), circuit breakers (Resilience4j), and comprehensive OpenTelemetry request tracing.
