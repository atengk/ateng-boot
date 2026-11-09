package io.github.atengk.boot.sample.http.service;

import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.sample.http.dto.FileDownloadVO;
import io.github.atengk.boot.sample.http.dto.SseStreamVO;
import io.github.atengk.boot.sample.http.dto.UserFormRequest;
import io.github.atengk.boot.sample.http.dto.UserOptionsRequest;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
}
