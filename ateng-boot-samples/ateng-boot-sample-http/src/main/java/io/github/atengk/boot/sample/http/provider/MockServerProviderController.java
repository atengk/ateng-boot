package io.github.atengk.boot.sample.http.provider;

import io.github.atengk.boot.sample.http.dto.FileUploadVO;
import io.github.atengk.boot.sample.http.dto.OrderResponse;
import io.github.atengk.boot.sample.http.dto.RemoteErrorPayload;
import io.github.atengk.boot.sample.http.dto.UserPatchRequest;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderRequest;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 样例工程内置离线 HTTP 回环仿真端点提供者
 * 充当本地与 CI 离线自洽场景下的模拟服务端，仅在非 local Profile 下激活，避免与真实直连联调冲突
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@Profile("!local")
@RestController
public class MockServerProviderController {

    private final AtomicInteger flakyCounter = new AtomicInteger(0);

    /**
     * 1. 查询指定用户详情（支持 GET/HEAD 元数据头透传，id<=0 或 404 返回 404 演示 Optional.empty()）
     */
    @GetMapping("/mock/provider/users/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable("id") Long id) {
        log.info("[MockProvider] Received getUser request for id: {}", id);
        if (id == null || id <= 0 || id == 404L) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Resource-Version", "v2")
                    .build();
        }
        UserResponse body = UserResponse.builder()
                .id(id)
                .username("user_" + id)
                .role("ADMIN")
                .status("ACTIVE")
                .build();
        return ResponseEntity.ok()
                .header("X-Resource-Version", "v2")
                .body(body);
    }

    /**
     * 2. 创建用户资源
     */
    @PostMapping("/mock/provider/users")
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
    @GetMapping("/mock/provider/flaky/reset")
    public ResponseEntity<Map<String, Object>> resetFlaky() {
        flakyCounter.set(0);
        return ResponseEntity.ok(Map.of("reset", true));
    }

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
    @GetMapping("/mock/provider/orders/{orderId}")
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
    @GetMapping("/mock/provider/orders/{orderId}/status")
    public OrderResponse getOrderStatus(@PathVariable("orderId") String orderId) {
        log.info("[MockProvider] Received getOrderStatus: orderId={}", orderId);
        return OrderResponse.builder()
                .orderId(orderId)
                .status("COMPLETED")
                .message("Mock order status")
                .build();
    }

    private final Map<String, String> lastReceivedHeaders = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 12. 外部微信支付统一下单模拟
     */
    @PostMapping("/mock/provider/wechat/v3/pay/transactions/native")
    public WechatNativeOrderResponse wechatNativeOrder(@RequestBody WechatNativeOrderRequest request, HttpServletRequest servletRequest) {
        lastReceivedHeaders.clear();
        Enumeration<String> names = servletRequest.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                lastReceivedHeaders.put(name.toLowerCase(), servletRequest.getHeader(name));
            }
        }
        log.info("[MockProvider] Received wechatNativeOrder: {}, headers: {}", request, lastReceivedHeaders);
        return WechatNativeOrderResponse.builder()
                .codeUrl("weixin://wxpay/bizpayurl?pr=mock_code_url_" + request.getOutTradeNo())
                .build();
    }

    /**
     * 获取最近一次微信下单接收到的物理请求头（用于验证敏感头物理剥离）
     */
    @GetMapping("/mock/provider/wechat/last-headers")
    public Map<String, String> getWechatLastHeaders() {
        return lastReceivedHeaders;
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

    /**
     * 14. 局部更新用户属性 (RFC 5789 HTTP PATCH 仿真)
     */
    @PatchMapping("/mock/provider/users/{id}")
    public UserResponse patchUser(@PathVariable("id") Long id, @RequestBody UserPatchRequest request) {
        log.info("[MockProvider] Received patchUser request for id: {}, payload: {}", id, request);
        return UserResponse.builder()
                .id(id)
                .username(StringUtils.hasText(request.getUsername()) ? request.getUsername() : "user_" + id)
                .role(StringUtils.hasText(request.getRole()) ? request.getRole() : "ADMIN")
                .status(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "ACTIVE")
                .build();
    }

    /**
     * 15. 用户端点能力探测 (HTTP OPTIONS 仿真，返回 Allow 集合与能力签名头)
     */
    @RequestMapping(value = "/mock/provider/users/{id}", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> optionsUser(@PathVariable("id") Long id) {
        log.info("[MockProvider] Received OPTIONS metadata probe for user id: {}", id);
        return ResponseEntity.ok()
                .allow(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
                        HttpMethod.HEAD, HttpMethod.OPTIONS)
                .header("X-Endpoint-Capability", "USER_CRUD_V2")
                .build();
    }

    /**
     * 16. 零缓冲流式 Multipart 分片文件上传仿真
     */
    @PostMapping(value = "/mock/provider/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileUploadVO uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", defaultValue = "sample-attachment") String bizType,
            HttpServletRequest request) throws IOException {
        byte[] content = file.getBytes();
        boolean internalHeaderClean = true;
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (headerName != null && (headerName.toLowerCase().startsWith("x-ateng-internal")
                        || headerName.toLowerCase().startsWith("x-ateng-request-options"))) {
                    internalHeaderClean = false;
                    break;
                }
            }
        }
        log.info("[MockProvider] Received multipart upload: filename={}, size={}, bizType={}, internalHeaderClean={}",
                file.getOriginalFilename(), content.length, bizType, internalHeaderClean);
        return FileUploadVO.builder()
                .filename(file.getOriginalFilename())
                .size(content.length)
                .bizType(bizType)
                .contentType(file.getContentType())
                .internalHeaderClean(internalHeaderClean)
                .build();
    }

    /**
     * 17. 结构化异常响应仿真（供 HttpRemoteException#getResponseBodyAs 反序列化验证）
     */
    @GetMapping("/mock/provider/errors/{statusCode}")
    public ResponseEntity<RemoteErrorPayload> simulateStructuredError(@PathVariable("statusCode") int statusCode) {
        int safeStatus = (statusCode >= 400 && statusCode <= 599) ? statusCode : HttpStatus.UNPROCESSABLE_ENTITY.value();
        log.info("[MockProvider] Simulating structured HTTP {} error payload", safeStatus);
        RemoteErrorPayload payload = RemoteErrorPayload.builder()
                .errorCode("DOWNSTREAM_" + safeStatus)
                .errorMessage("模拟下游系统结构化业务异常: HTTP " + safeStatus)
                .requestId("REQ_ERR_" + safeStatus)
                .build();
        return ResponseEntity.status(safeStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }
}
