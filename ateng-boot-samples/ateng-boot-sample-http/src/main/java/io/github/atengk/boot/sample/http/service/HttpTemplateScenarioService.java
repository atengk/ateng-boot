package io.github.atengk.boot.sample.http.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.http.error.HttpResultCode;
import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.sample.http.dto.AsyncContextVO;
import io.github.atengk.boot.sample.http.dto.EndpointMetadataVO;
import io.github.atengk.boot.sample.http.dto.FileDownloadVO;
import io.github.atengk.boot.sample.http.dto.FileUploadVO;
import io.github.atengk.boot.sample.http.dto.OptionalLookupVO;
import io.github.atengk.boot.sample.http.dto.ResponseEntityVO;
import io.github.atengk.boot.sample.http.dto.SseStreamVO;
import io.github.atengk.boot.sample.http.dto.UserFormRequest;
import io.github.atengk.boot.sample.http.dto.UserOptionsRequest;
import io.github.atengk.boot.sample.http.dto.UserPatchRequest;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * HttpTemplate 命令式 HTTP 通信业务场景服务
 * 封装命令式动词操作、单次 RequestOptions 动态微调、表单提交与流式传输
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpTemplateScenarioService {

    private final HttpTemplate httpTemplate;

    @Autowired(required = false)
    private ServletWebServerApplicationContext webServerContext;

    /**
     * 获取当前服务回环访问基地址
     *
     * @return 当前运行的基准 URL
     */
    public String getBaseUrl() {
        int port = (webServerContext != null && webServerContext.getWebServer() != null)
                ? webServerContext.getWebServer().getPort()
                : 8080;
        return "http://localhost:" + port;
    }

    /**
     * 1. 命令式 GET 获取用户详情
     */
    public UserResponse getUserById(Long id) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Invoking GET {}", url);
        return httpTemplate.get(url, UserResponse.class);
    }

    /**
     * 2. 命令式 POST 创建用户
     */
    public UserResponse createUser(UserRequest request) {
        String url = getBaseUrl() + "/mock/provider/users";
        log.info("[HttpTemplateService] Invoking POST {} with payload {}", url, request);
        return httpTemplate.post(url, request, UserResponse.class);
    }

    /**
     * 3. 命令式 PUT 全量更新用户
     */
    public UserResponse updateUser(Long id, UserRequest request) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Invoking PUT {} with payload {}", url, request);
        return httpTemplate.put(url, request, UserResponse.class);
    }

    /**
     * 4. 命令式 DELETE 删除用户
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deleteUser(Long id) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Invoking DELETE {}", url);
        return httpTemplate.delete(url, Map.class);
    }

    /**
     * 5. 携带单次动态 RequestOptions（动态超时、自定义头、Query 参数）
     */
    public UserResponse getUserWithOptions(UserOptionsRequest request) {
        String url = getBaseUrl() + "/mock/provider/users/" + request.getId();
        RequestOptions.Builder builder = RequestOptions.builder();
        if (request.getReadTimeoutMillis() != null && request.getReadTimeoutMillis() > 0) {
            builder.readTimeout(Duration.ofMillis(request.getReadTimeoutMillis()));
        }
        if (request.getCustomHeader() != null) {
            builder.header("X-Custom-Option-Header", request.getCustomHeader());
        }
        if (request.getCustomQueryParam() != null) {
            builder.queryParam("customParam", request.getCustomQueryParam());
        }
        RequestOptions options = builder.build();
        log.info("[HttpTemplateService] Invoking GET {} with dynamic options: {}", url, options);
        return httpTemplate.get(url, options, UserResponse.class);
    }

    /**
     * 6. application/x-www-form-urlencoded 标准表单提交
     */
    public UserResponse submitUserForm(UserFormRequest request) {
        String url = getBaseUrl() + "/mock/provider/users/form";
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("username", request.getUsername());
        if (request.getEmail() != null) {
            formData.add("email", request.getEmail());
        }
        if (request.getRole() != null) {
            formData.add("role", request.getRole());
        }
        log.info("[HttpTemplateService] Submitting form POST to {} with form data: {}", url, formData);
        return httpTemplate.postForm(url, formData, UserResponse.class);
    }

    /**
     * 7. 消费 SSE 文本事件流
     */
    public SseStreamVO consumeSseStream() {
        String url = getBaseUrl() + "/mock/provider/stream/sse";
        log.info("[HttpTemplateService] Consuming SSE text stream from {}", url);
        List<String> events = new ArrayList<>();
        httpTemplate.consumeStream(url, line -> {
            if (line != null && !line.isBlank()) {
                events.add(line);
            }
        });
        return SseStreamVO.builder()
                .eventCount(events.size())
                .events(events)
                .build();
    }

    /**
     * 8. 大文件零缓冲流式下载
     */
    public FileDownloadVO downloadFile() {
        String url = getBaseUrl() + "/mock/provider/files/download";
        log.info("[HttpTemplateService] Streaming download from {}", url);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        httpTemplate.download(url, outputStream);
        byte[] bytes = outputStream.toByteArray();
        return FileDownloadVO.builder()
                .size(bytes.length)
                .filename("sample-file.txt")
                .previewContent(new String(bytes, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 9. 命令式 PATCH 局部更新用户属性 (RFC 5789)
     *
     * @param id      用户标识
     * @param request 局部更新载荷
     * @return 更新后的用户响应视图
     */
    public UserResponse patchUser(Long id, UserPatchRequest request) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Invoking PATCH {} with payload {}", url, request);
        return httpTemplate.patch(url, request, UserResponse.class);
    }

    /**
     * 10. 命令式 HEAD 与 OPTIONS 组合元数据探测
     *
     * @param id 目标资源标识
     * @return 包含 HEAD 元数据头与 OPTIONS 允许动词的视图对象
     */
    public EndpointMetadataVO probeUserMetadata(Long id) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Probing HEAD/OPTIONS metadata for {}", url);
        HttpHeaders headHeaders = httpTemplate.head(url);
        HttpHeaders optionsHeaders = httpTemplate.options(url);
        Set<HttpMethod> allowed = httpTemplate.optionsForAllow(url);
        Set<String> sortedMethods = allowed.stream()
                .map(HttpMethod::name)
                .collect(Collectors.toCollection(TreeSet::new));
        return EndpointMetadataVO.builder()
                .resourceId(id)
                .contentType(headHeaders != null && headHeaders.getContentType() != null
                        ? headHeaders.getContentType().toString()
                        : null)
                .contentLength(headHeaders != null ? headHeaders.getContentLength() : -1L)
                .resourceVersion(headHeaders != null ? headHeaders.getFirst("X-Resource-Version") : null)
                .endpointCapability(optionsHeaders != null ? optionsHeaders.getFirst("X-Endpoint-Capability") : null)
                .allowedMethods(sortedMethods)
                .optionsHeaderCount(optionsHeaders != null ? optionsHeaders.size() : 0)
                .build();
    }

    /**
     * 11. 空安全 getOptional 查询（下游返回 404 时自动转换为 Optional.empty()）
     *
     * @param id 用户标识（传入 404 或非正数触发下游 404 响应）
     * @return 空安全查询结果视图
     */
    public OptionalLookupVO getUserOptional(Long id) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Invoking getOptional {}", url);
        Optional<UserResponse> result = httpTemplate.getOptional(url, UserResponse.class);
        return OptionalLookupVO.builder()
                .id(id)
                .found(result.isPresent())
                .statusSummary(result.isPresent() ? "FOUND" : "NOT_FOUND_EMPTY")
                .user(result.orElse(null))
                .build();
    }

    /**
     * 12. getEntity 提取完整 HTTP 状态码、响应头与实体载荷
     *
     * @param id 用户标识
     * @return 包含状态码、自定义响应头与实体的视图对象
     */
    public ResponseEntityVO getUserEntity(Long id) {
        String url = getBaseUrl() + "/mock/provider/users/" + id;
        log.info("[HttpTemplateService] Invoking getEntity {}", url);
        ResponseEntity<UserResponse> entity = httpTemplate.getEntity(url, UserResponse.class);
        return ResponseEntityVO.builder()
                .httpStatus(entity.getStatusCode().value())
                .resourceVersion(entity.getHeaders().getFirst("X-Resource-Version"))
                .contentType(entity.getHeaders().getContentType() != null
                        ? entity.getHeaders().getContentType().toString()
                        : null)
                .body(entity.getBody())
                .build();
    }

    /**
     * 13. 零缓冲流式 Multipart 分片文件上传
     *
     * @param file    待上传分片文件
     * @param bizType 业务分类附加表单字段
     * @return 上传回执视图
     */
    public FileUploadVO uploadFile(MultipartFile file, String bizType) {
        BusinessAssert.isTrue(file != null && !file.isEmpty(), "上传文件不能为空");
        String resolvedBizType = StringUtils.hasText(bizType) ? bizType.trim() : "sample-attachment";
        String url = getBaseUrl() + "/mock/provider/files/upload";
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", file.getResource());
        parts.add("bizType", resolvedBizType);
        RequestOptions options = RequestOptions.builder()
                .header("X-Upload-Biz-Type", resolvedBizType)
                .build();
        log.info("[HttpTemplateService] Streaming multipart upload to {} [filename={}, size={}]",
                url, file.getOriginalFilename(), file.getSize());
        return httpTemplate.upload(url, parts, options, FileUploadVO.class);
    }

    /**
     * 14. 基于 Core 受管线程池的异步调用与四维上下文保真透传
     *
     * @return 主线程与异步线程四维上下文校验结果
     */
    public AsyncContextVO executeAsyncContext() {
        UserContextHolder.UserSnapshot previousUser = UserContextHolder.capture();
        TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capture();
        String previousTraceId = MDC.get("traceId");

        String callerTraceId = StringUtils.hasText(previousTraceId) ? previousTraceId : IdUtil.fastSimpleUUID();
        MDC.put("traceId", callerTraceId);
        UserContextHolder.setUserId("USER_ASYNC_777");
        TenantContextHolder.setTenantId("TENANT_ASYNC_999");
        TenantContextHolder.setIgnoreTenant(true);
        try {
            String url = getBaseUrl() + "/mock/provider/users/1001";
            CompletableFuture<AsyncContextVO> future = httpTemplate.executeAsync(() -> {
                String asyncTraceId = MDC.get("traceId");
                String asyncUserId = UserContextHolder.getUserId();
                String asyncTenantId = TenantContextHolder.getTenantId();
                boolean asyncIgnoreTenant = TenantContextHolder.isIgnoreTenant();
                UserResponse remoteUser = httpTemplate.get(url, UserResponse.class);
                boolean propagatedAllFour = callerTraceId.equals(asyncTraceId)
                        && "USER_ASYNC_777".equals(asyncUserId)
                        && "TENANT_ASYNC_999".equals(asyncTenantId)
                        && asyncIgnoreTenant;
                return AsyncContextVO.builder()
                        .callerTraceId(callerTraceId)
                        .asyncTraceId(asyncTraceId)
                        .asyncUserId(asyncUserId)
                        .asyncTenantId(asyncTenantId)
                        .asyncIgnoreTenant(asyncIgnoreTenant)
                        .propagatedAllFour(propagatedAllFour)
                        .remoteUser(remoteUser)
                        .build();
            });
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpResultCode.REMOTE_HTTP_SERVER_ERROR, "异步执行四维上下文演示任务被中断", ex);
        } catch (TimeoutException ex) {
            throw new BusinessException(HttpResultCode.REMOTE_HTTP_TIMEOUT, "异步执行四维上下文演示任务超时", ex);
        } catch (ExecutionException ex) {
            throw new BusinessException(HttpResultCode.REMOTE_HTTP_SERVER_ERROR, "异步执行四维上下文演示任务失败", ex);
        } finally {
            TenantContextHolder.restore(previousTenant);
            UserContextHolder.restore(previousUser);
            if (previousTraceId == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previousTraceId);
            }
        }
    }
}
