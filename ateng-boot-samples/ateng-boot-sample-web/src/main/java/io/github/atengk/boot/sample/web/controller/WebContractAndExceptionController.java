package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.core.validation.ValidationGroups;
import io.github.atengk.boot.sample.web.dto.UserRegisterDTO;
import io.github.atengk.boot.sample.web.dto.WebEnumUserRequest;
import io.github.atengk.boot.sample.web.dto.WebEnumUserResponse;
import io.github.atengk.boot.sample.web.exception.PaymentChannelException;
import io.github.atengk.boot.sample.web.vo.DateTimeDemoVO;
import io.github.atengk.boot.sample.web.vo.UserRegisterVO;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 传输契约、序列化规范、异常防御与枚举绑定第一领域场景控制器
 * 严格践行 ADR-0002 显式 Result 契约，杜绝 @ResponseBodyAdvice，全真演示 JSR-303、ValidList、分组校验、枚举反序列化与异常脱敏
 *
 * @author Ateng
 * @since 2026-11-07
 */
@RestController
@RequestMapping("/sample/web/contract")
public class WebContractAndExceptionController {

    /**
     * 会员实名注册（语法与格式第一道防线校验）
     *
     * @param dto 会员注册载荷
     * @return 显式强类型契约 Result<UserRegisterVO>
     */
    @PostMapping("/user")
    public Result<UserRegisterVO> createUser(@Valid @RequestBody UserRegisterDTO dto) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(1846182948192841920L)
                .name(dto.getName())
                .age(dto.getAge())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .build();
        return Result.success(vo);
    }

    /**
     * 渠道商批量入驻校验（防 Spring 默认 List 漏检，依托 ValidList 实现 Fail-Fast）
     *
     * @param users 批量用户列表载荷
     * @return 注册成功的用户总数
     */
    @PostMapping("/users/batch")
    public Result<Integer> batchCreateUsers(@Valid @RequestBody ValidList<UserRegisterDTO> users) {
        return Result.success(users.size());
    }

    /**
     * 会员信息更新（演示 ValidationGroups.Update 分组校验，必须校验 id 非空）
     *
     * @param dto 会员更新载荷
     * @return 更新后的视图对象
     */
    @PutMapping("/user")
    public Result<UserRegisterVO> updateUser(@Validated({Default.class, ValidationGroups.Update.class}) @RequestBody UserRegisterDTO dto) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .age(dto.getAge())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .build();
        return Result.success(vo);
    }

    /**
     * 日期时间与雪花 ID 序列化格式规范演示端点
     *
     * @return 日期时间视图
     */
    @GetMapping("/datetime")
    public Result<DateTimeDemoVO> getDateTimeDemo() {
        DateTimeDemoVO vo = DateTimeDemoVO.builder()
                .id(1846182948192841920L)
                .dateTime(LocalDateTime.of(2026, 10, 19, 14, 30, 0))
                .date(LocalDate.of(2026, 10, 19))
                .time(LocalTime.of(14, 30, 0))
                .build();
        return Result.success(vo);
    }

    /**
     * 枚举入参校验与强类型双向反序列化绑定
     *
     * @param request 枚举绑定请求
     * @return 枚举响应
     */
    @PostMapping("/enum-bind")
    public Result<WebEnumUserResponse> bindEnum(@Valid @RequestBody WebEnumUserRequest request) {
        return Result.success(new WebEnumUserResponse(
                request.getUsername(),
                request.getStatusCode(),
                request.getStatus(),
                request.getOrderType()
        ));
    }

    /**
     * 业务受控异常中断演示（无 cause：轻量级日志记录，返回业务错误码）
     *
     * @return 无返回值
     */
    @GetMapping("/error/business")
    public Result<Void> triggerBusinessException() {
        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "用户积分余额不足");
    }

    /**
     * 携带底层驱动 cause 根因的因果业务异常（保留完整异常因果链）
     *
     * @return 无返回值
     */
    @GetMapping("/error/causal")
    public Result<Void> triggerCausalException() {
        throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "网关代理服务通信异常",
                new IOException("Connection reset by peer"));
    }

    /**
     * 未捕获未知根异常（生产强制脱敏，禁止泄露内部代码堆栈，MDC traceId 闭环溯源）
     *
     * @return 无返回值
     */
    @GetMapping("/error/unexpected")
    public Result<Void> triggerUnexpectedException() {
        throw new NullPointerException("内部系统模拟空指针异常");
    }

    /**
     * 触发第三方支付通道业务领域异常（经由 PaymentExceptionCustomizer SPI 转译）
     *
     * @return 业务响应契约
     */
    @GetMapping("/error/payment")
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
    @PostMapping(value = "/error/media-type", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> testMediaType(@RequestBody Map<String, Object> body) {
        return Result.success(body);
    }

    /**
     * 演示文件上传及必须携带文件分片（缺少 file 分片时触发 MissingServletRequestPartException）
     *
     * @param file 上传的文件
     * @return 文件名
     */
    @PostMapping(value = "/error/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> uploadFile(@RequestPart("file") MultipartFile file) {
        return Result.success(file.getOriginalFilename());
    }

    /**
     * 触发大文件超限异常（MaxUploadSizeExceededException，转译为 413 Payload Too Large）
     *
     * @return 业务响应契约
     */
    @GetMapping("/error/payload-too-large")
    public Result<Void> triggerMaxUploadSizeExceeded() {
        throw new MaxUploadSizeExceededException(10485760L);
    }

    /**
     * 触发异步请求处理超时异常（AsyncRequestTimeoutException，转译为 503 Service Unavailable）
     *
     * @return 业务响应契约
     */
    @GetMapping("/error/async-timeout")
    public Result<Void> triggerAsyncTimeout() {
        throw new AsyncRequestTimeoutException();
    }

    /**
     * 会员契约解包与安全校验（具备真实业务语义的返回）
     *
     * @return 会员协议契约快照
     */
    @GetMapping("/unpack")
    public Result<Map<String, Object>> unpackDemo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ACTIVE");
        data.put("contractVersion", "2026.1");
        data.put("verified", true);
        return Result.success(data);
    }
}
