package io.github.atengk.boot.sample.openapi.controller.admin;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.openapi.annotation.IgnoreGlobalHeaders;
import io.github.atengk.boot.sample.openapi.model.LoginRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营管理端 - 开放认证与探针控制器
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Tag(name = "运营管理端 - 开放认证", description = "面向管理员的登录鉴权开放端点，演示 @IgnoreGlobalHeaders 声明式请求头豁免机制")
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    /**
     * 登录认证接口（精准豁免 X-Tenant-Id）
     *
     * @param request 登录凭据
     * @return 访问 Token
     */
    @Operation(summary = "管理员登录认证", description = "开放登录接口，声明式精准豁免 X-Tenant-Id 租户头")
    @IgnoreGlobalHeaders({"X-Tenant-Id"})
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("mock-jwt-token-12345");
    }

    /**
     * 系统探针接口（全量豁免所有全局请求头）
     *
     * @return 探针心跳
     */
    @Operation(summary = "公开系统心跳探针", description = "完全公开的监控端点，全量豁免所有全局请求头")
    @IgnoreGlobalHeaders
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }
}
