package io.github.atengk.boot.sample.smoke.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.smoke.model.SmokeBatchItemRequest;
import io.github.atengk.boot.sample.smoke.model.SmokePingVO;
import io.github.atengk.boot.sample.smoke.model.SmokeSanitizedVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 基础契约与校验领域控制器
 * 验证 Result 显式响应、X-Trace-Id 链路穿透、Long 转 String 防精度丢失、ValidList 级联校验与 @FieldSensitive 脱敏
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Tag(name = "1. 基础契约与校验")
@RestController
@RequestMapping("/sample/smoke/contract")
public class SmokeContractController {

    private static final long SNOWFLAKE_PROBE_ID = 9007199254740993L;

    /**
     * 冒烟探活与基础契约检查
     *
     * @return 包含大整数探测值与链路标识的统一响应
     */
    @Operation(summary = "冒烟探活与基础契约检查")
    @GetMapping("/ping")
    public Result<SmokePingVO> ping() {
        SmokePingVO vo = new SmokePingVO(
                "ateng-boot-sample-smoke-test",
                "1.2.0",
                SNOWFLAKE_PROBE_ID,
                MDC.get("traceId")
        );
        return Result.success(vo);
    }

    /**
     * 批量列表 JSR-303 级联校验与视图层动态脱敏演示
     *
     * @param items 受 ValidList 包装的批量校验请求列表
     * @return Jackson 序列化时自动脱敏的视图对象列表
     */
    @Operation(summary = "ValidList 级联校验与视图脱敏")
    @PostMapping("/batch-validate")
    public Result<List<SmokeSanitizedVO>> batchValidate(@RequestBody @Valid ValidList<SmokeBatchItemRequest> items) {
        List<SmokeSanitizedVO> result = items.stream()
                .map(item -> new SmokeSanitizedVO(item.getTitle(), item.getStatus(), item.getMobile()))
                .toList();
        return Result.success(result);
    }
}
