package io.github.atengk.boot.sample.http.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.http.dto.FileDownloadVO;
import io.github.atengk.boot.sample.http.dto.SseStreamVO;
import io.github.atengk.boot.sample.http.dto.UserFormRequest;
import io.github.atengk.boot.sample.http.dto.UserOptionsRequest;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import io.github.atengk.boot.sample.http.service.HttpTemplateScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * HttpTemplate 命令式 HTTP 通信业务控制器
 * 提供标准 REST 动词 CRUD、单次 RequestOptions 超时/请求头覆盖、表单提交、SSE 流式消费与大文件零缓冲下载体验
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
}
