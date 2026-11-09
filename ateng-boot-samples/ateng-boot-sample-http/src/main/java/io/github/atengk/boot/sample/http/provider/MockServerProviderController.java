package io.github.atengk.boot.sample.http.provider;

import io.github.atengk.boot.sample.http.dto.OrderResponse;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderRequest;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 样例工程内置离线 HTTP 回环仿真端点提供者
 * 充当本地与 CI 离线自洽场景下的模拟服务端，为 HttpTemplate 与声明式客户端提供真实的物理网络端点
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
public class MockServerProviderController {

    private final AtomicInteger flakyCounter = new AtomicInteger(0);

    /**
     * 1. 查询指定用户详情
     */
    @GetMapping({"/mock/provider/users/{id}", "/users/{id}"})
    public UserResponse getUser(@PathVariable("id") Long id) {
        log.info("[MockProvider] Received getUser request for id: {}", id);
        return UserResponse.builder()
                .id(id)
                .username("user_" + id)
                .role("ADMIN")
                .status("ACTIVE")
                .build();
    }

    /**
     * 2. 创建用户资源
     */
    @PostMapping({"/mock/provider/users", "/users"})
    public UserResponse createUser(@RequestBody UserRequest request) {
        log.info("[MockProvider] Received createUser request: {}", request);
        return UserResponse.builder()
                .id(2001L)
                .username(request.getUsername())
                .role(request.getRole() != null ? request.getRole() : "USER")
                .status("ACTIVE")
                .build();
    }

    /**
     * 3. 更新用户资源
     */
    @PutMapping("/mock/provider/users/{id}")
    public UserResponse updateUser(@PathVariable("id") Long id, @RequestBody UserRequest request) {
        log.info("[MockProvider] Received updateUser request for id: {}, data: {}", id, request);
        return UserResponse.builder()
                .id(id)
                .username(request.getUsername())
                .role(request.getRole() != null ? request.getRole() : "UPDATED")
                .status("ACTIVE")
                .build();
    }

    /**
     * 4. 删除用户资源
     */
    @DeleteMapping("/mock/provider/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable("id") Long id) {
        log.info("[MockProvider] Received deleteUser request for id: {}", id);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", true);
        result.put("id", id);
        return result;
    }

    /**
     * 5. 表单提交端点
     */
    @PostMapping(value = "/mock/provider/users/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public UserResponse submitUserForm(@RequestParam("username") String username,
                                       @RequestParam(value = "email", required = false) String email,
                                       @RequestParam(value = "role", required = false) String role) {
        log.info("[MockProvider] Received form submission: username={}, email={}, role={}", username, email, role);
        return UserResponse.builder()
                .id(3001L)
                .username(username)
                .role(role != null ? role : "FORM_USER")
                .status("ACTIVE")
                .build();
    }

    /**
     * 6. SSE 文本事件流
     */
    @GetMapping(value = "/mock/provider/stream/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void sseStream(HttpServletResponse response) throws IOException {
        log.info("[MockProvider] Received SSE stream subscription request");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter writer = response.getWriter();
        writer.write("data: sse-event-001\n\n");
        writer.write("data: sse-event-002\n\n");
        writer.write("data: sse-event-003\n\n");
        writer.flush();
    }

    /**
     * 7. 二进制流式下载
     */
    @GetMapping(value = "/mock/provider/files/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadFile() {
        log.info("[MockProvider] Received file download request");
        byte[] content = "Hello ateng-boot-http streaming download payload content!".getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample-file.txt\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /**
     * 8. 慢响应时延模拟
     */
    @GetMapping("/mock/provider/delay")
    public Map<String, Object> delay(@RequestParam(value = "millis", defaultValue = "1000") long millis) {
        log.info("[MockProvider] Simulating latency delay: {} ms", millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("status", "SUCCESS");
        map.put("delayedMillis", millis);
        return map;
    }

    /**
     * 9. 瞬态 503 与重试演练模拟端点
     */
    @GetMapping("/mock/provider/flaky")
    public ResponseEntity<Map<String, Object>> flaky(
            @RequestParam(value = "failTimes", defaultValue = "2") int failTimes,
            @RequestParam(value = "reset", defaultValue = "false") boolean reset) {
        if (reset) {
            flakyCounter.set(0);
        }
        int current = flakyCounter.incrementAndGet();
        log.info("[MockProvider] Flaky endpoint attempt: {}, targetFailTimes: {}", current, failTimes);
        if (current <= failTimes) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Service Unavailable");
            error.put("currentAttempt", current);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(error);
        }
        Map<String, Object> ok = new HashMap<>();
        ok.put("status", "RECOVERED");
        ok.put("successfulAttempt", current);
        return ResponseEntity.ok(ok);
    }

    /**
     * 10. 订单详情（支持注入时延以测试客户端方法级超时与降级）
     */
    @GetMapping({"/mock/provider/orders/{orderId}", "/orders/{orderId}"})
    public ResponseEntity<OrderResponse> getOrder(@PathVariable("orderId") String orderId,
                                                  @RequestParam(value = "delayMillis", defaultValue = "0") long delayMillis) {
        log.info("[MockProvider] Received getOrder: orderId={}, delayMillis={}", orderId, delayMillis);
        if (delayMillis > 0 || orderId.contains("slow") || orderId.contains("timeout")) {
            long sleepTime = delayMillis > 0 ? delayMillis : 1000L;
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (orderId.contains("error") || orderId.contains("fail")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(OrderResponse.builder()
                            .orderId(orderId)
                            .status("FAILED")
                            .message("Simulated downstream error for order: " + orderId)
                            .build());
        }
        return ResponseEntity.ok(OrderResponse.builder()
                .orderId(orderId)
                .status("SUCCESS")
                .message("Mock order details fetched successfully")
                .build());
    }

    /**
     * 11. 订单状态查询
     */
    @GetMapping({"/mock/provider/orders/{orderId}/status", "/orders/{orderId}/status"})
    public OrderResponse getOrderStatus(@PathVariable("orderId") String orderId) {
        log.info("[MockProvider] Received getOrderStatus: orderId={}", orderId);
        return OrderResponse.builder()
                .orderId(orderId)
                .status("COMPLETED")
                .message("Mock order status")
                .build();
    }

    /**
     * 12. 外部微信支付统一下单模拟
     */
    @PostMapping("/mock/provider/wechat/v3/pay/transactions/native")
    public WechatNativeOrderResponse wechatNativeOrder(@RequestBody WechatNativeOrderRequest request) {
        log.info("[MockProvider] Received wechatNativeOrder: {}", request);
        return WechatNativeOrderResponse.builder()
                .codeUrl("weixin://wxpay/bizpayurl?pr=mock_code_url_" + request.getOutTradeNo())
                .build();
    }

    /**
     * 13. 请求头反射回显（用于核验证书/MDC/敏感头剥离）
     */
    @GetMapping("/mock/provider/headers/echo")
    public Map<String, String> echoHeaders(HttpServletRequest request) {
        Map<String, String> map = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                map.put(name.toLowerCase(), request.getHeader(name));
            }
        }
        return map;
    }
}
