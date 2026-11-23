package io.github.atengk.boot.sample.http.controller;

import io.github.atengk.boot.core.model.Result;
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
import io.github.atengk.boot.sample.http.service.HttpTemplateScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * HttpTemplate 命令式 HTTP 通信业务控制器
 * 提供全动词 REST CRUD、HEAD/OPTIONS 元数据探测、空安全 getOptional、响应实体提取、流式上传下载与异步上下文透传体验
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/http/template")
@RequiredArgsConstructor
public class HttpTemplateController {

    private final HttpTemplateScenarioService httpTemplateScenarioService;

    /**
     * 1. 命令式 GET 查询用户详情
     */
    @GetMapping("/user/{id}")
    public Result<UserResponse> getUserById(@PathVariable("id") Long id) {
        UserResponse response = httpTemplateScenarioService.getUserById(id);
        return Result.success(response);
    }

    /**
     * 2. 命令式 POST 创建用户资源
     */
    @PostMapping("/user")
    public Result<UserResponse> createUser(@Valid @RequestBody UserRequest request) {
        UserResponse response = httpTemplateScenarioService.createUser(request);
        return Result.success(response);
    }

    /**
     * 3. 命令式 PUT 全量更新用户
     */
    @PutMapping("/user/{id}")
    public Result<UserResponse> updateUser(@PathVariable("id") Long id, @Valid @RequestBody UserRequest request) {
        UserResponse response = httpTemplateScenarioService.updateUser(id, request);
        return Result.success(response);
    }

    /**
     * 4. 命令式 DELETE 删除指定资源
     */
    @DeleteMapping("/user/{id}")
    public Result<Map<String, Object>> deleteUser(@PathVariable("id") Long id) {
        Map<String, Object> result = httpTemplateScenarioService.deleteUser(id);
        return Result.success(result);
    }

    /**
     * 5. 携带单次动态 RequestOptions（动态超时、自定义头、Query 参数）
     */
    @PostMapping("/user/options")
    public Result<UserResponse> getUserWithOptions(@Valid @RequestBody UserOptionsRequest request) {
        UserResponse response = httpTemplateScenarioService.getUserWithOptions(request);
        return Result.success(response);
    }

    /**
     * 6. application/x-www-form-urlencoded 标准表单提交
     */
    @PostMapping("/user/form")
    public Result<UserResponse> submitUserForm(@Valid @RequestBody UserFormRequest request) {
        UserResponse response = httpTemplateScenarioService.submitUserForm(request);
        return Result.success(response);
    }

    /**
     * 7. 消费 SSE 文本事件流
     */
    @GetMapping("/stream/sse")
    public Result<SseStreamVO> consumeSseStream() {
        SseStreamVO response = httpTemplateScenarioService.consumeSseStream();
        return Result.success(response);
    }

    /**
     * 8. 大文件零缓冲流式下载
     */
    @GetMapping("/download")
    public Result<FileDownloadVO> downloadFile() {
        FileDownloadVO response = httpTemplateScenarioService.downloadFile();
        return Result.success(response);
    }

    /**
     * 9. 命令式 PATCH 局部更新用户属性
     */
    @PatchMapping("/user/{id}")
    public Result<UserResponse> patchUser(@PathVariable("id") Long id, @RequestBody UserPatchRequest request) {
        UserResponse response = httpTemplateScenarioService.patchUser(id, request);
        return Result.success(response);
    }

    /**
     * 10. HEAD / OPTIONS 端点元数据与 Allow 动词探测
     */
    @GetMapping("/metadata/{id}")
    public Result<EndpointMetadataVO> probeUserMetadata(@PathVariable("id") Long id) {
        EndpointMetadataVO response = httpTemplateScenarioService.probeUserMetadata(id);
        return Result.success(response);
    }

    /**
     * 11. 空安全 getOptional 查询（404 自动转换为 Optional.empty()）
     */
    @GetMapping("/get-optional/{id}")
    public Result<OptionalLookupVO> getUserOptional(@PathVariable("id") Long id) {
        OptionalLookupVO response = httpTemplateScenarioService.getUserOptional(id);
        return Result.success(response);
    }

    /**
     * 12. getEntity 响应状态码、自定义响应头与实体载荷提取
     */
    @GetMapping("/get-entity/{id}")
    public Result<ResponseEntityVO> getUserEntity(@PathVariable("id") Long id) {
        ResponseEntityVO response = httpTemplateScenarioService.getUserEntity(id);
        return Result.success(response);
    }

    /**
     * 13. 零缓冲流式 Multipart 分片文件上传
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<FileUploadVO> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", defaultValue = "sample-attachment") String bizType) {
        FileUploadVO response = httpTemplateScenarioService.uploadFile(file, bizType);
        return Result.success(response);
    }

    /**
     * 14. 异步受管线程池调用与四维上下文保真透传演示
     */
    @GetMapping("/async-context")
    public Result<AsyncContextVO> executeAsyncContext() {
        AsyncContextVO response = httpTemplateScenarioService.executeAsyncContext();
        return Result.success(response);
    }
}
