package io.github.atengk.boot.sample.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.dto.OrderCreateDTO;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 接口防重幂等示例控制层
 * 演示 SpEL 业务键防重、通用按钮指纹防抖与异常自动释放重试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@RestController
@RequestMapping("/api/samples/idempotent")
public class IdempotentSampleController {

    /**
     * 精准 SpEL 业务键防重演示端点
     *
     * @param dto 创建订单 DTO
     * @return 订单号
     */
    @PostMapping("/order")
    @Idempotent(key = "#dto.orderNo", prefix = "order:", timeout = 2, message = "订单处理中，请勿重复提交")
    public Result<String> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success(dto.getOrderNo());
    }

    /**
     * 通用按钮防抖（无参默认请求特征指纹哈希）演示端点
     *
     * @param formToken 表单参数
     * @return 响应结果
     */
    @PostMapping("/button")
    @Idempotent(timeout = 2)
    public Result<String> submitButton(@RequestParam String formToken) {
        return Result.success("submitted: " + formToken);
    }

    /**
     * 业务异常自动释放锁重试演示端点
     *
     * @param bizId 业务主键
     * @param fail  是否触发异常
     * @return 响应结果
     */
    @PostMapping("/fail-retry")
    @Idempotent(key = "#bizId", timeout = 5, delKeyOnException = true)
    public Result<String> failAndRetry(@RequestParam String bizId, @RequestParam boolean fail) {
        if (fail) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "可恢复业务校验失败");
        }
        return Result.success("success: " + bizId);
    }
}
