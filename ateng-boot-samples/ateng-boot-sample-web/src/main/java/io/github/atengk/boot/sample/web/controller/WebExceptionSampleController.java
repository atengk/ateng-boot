package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.exception.PaymentChannelException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 全局异常体系精细化转译与扩展 SPI 场景控制器
 * 演示领域异常转译 SPI（GlobalExceptionCustomizer）、413 大文件超限、415 不支持的媒体类型、406 不接受的媒体类型、503 异步超时与 400 缺少分片
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/sample/exception")
public class WebExceptionSampleController {

    /**
     * 触发第三方支付通道业务领域异常（经由 PaymentExceptionCustomizer SPI 转译）
     *
     * @return 业务响应契约
     */
    @GetMapping("/payment")
    public Result<Void> triggerPaymentException() {
        throw new PaymentChannelException("ALIPAY", "通道账户可用余额不足");
    }

    /**
     * 严格限制仅消费 application/json 并产生 application/json 的接口
     * 若客户端发送非 JSON Content-Type 则触发 415，若客户端请求不支持的 Accept 则触发 406
     *
     * @param body 请求体数据
     * @return 回显数据
     */
    @PostMapping(value = "/media-type", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> testMediaType(@RequestBody Map<String, Object> body) {
        return Result.success(body);
    }

    /**
     * 演示文件上传及必须携带文件分片（缺少 file 分片时触发 MissingServletRequestPartException）
     *
     * @param file 上传的文件
     * @return 文件名
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadFile(@RequestPart("file") MultipartFile file) {
        return Result.success(file.getOriginalFilename());
    }

    /**
     * 触发大文件超限异常（MaxUploadSizeExceededException，转译为 413 Payload Too Large）
     *
     * @return 业务响应契约
     */
    @GetMapping("/payload-too-large")
    public Result<Void> triggerMaxUploadSizeExceeded() {
        throw new MaxUploadSizeExceededException(10485760L);
    }

    /**
     * 触发异步请求处理超时异常（AsyncRequestTimeoutException，转译为 503 Service Unavailable）
     *
     * @return 业务响应契约
     */
    @GetMapping("/async-timeout")
    public Result<Void> triggerAsyncTimeout() {
        throw new AsyncRequestTimeoutException();
    }
}
