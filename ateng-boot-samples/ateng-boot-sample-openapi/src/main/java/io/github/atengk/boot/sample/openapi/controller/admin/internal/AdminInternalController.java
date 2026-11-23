package io.github.atengk.boot.sample.openapi.controller.admin.internal;

import cn.dev33.satoken.annotation.SaIgnore;
import io.github.atengk.boot.core.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营管理端内部诊断控制器（用于验证分组 packages-to-exclude 对称排除规则）
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Tag(name = "运营管理端 - 内部诊断", description = "仅在全量文档暴露、在 admin 业务分组中通过 packages-to-exclude 剔除的内部运维探针")
@RestController
@RequestMapping("/sample/openapi/admin/internal")
public class AdminInternalController {

    /**
     * 获取内部诊断指标状态
     *
     * @return 诊断指标标识
     */
    @Operation(summary = "获取内部运行指标", description = "内部排障专用探针，应被 admin 分组包排除规则剔除")
    @SaIgnore
    @GetMapping("/metrics")
    public Result<String> metrics() {
        return Result.success("INTERNAL_METRICS_OK");
    }
}
