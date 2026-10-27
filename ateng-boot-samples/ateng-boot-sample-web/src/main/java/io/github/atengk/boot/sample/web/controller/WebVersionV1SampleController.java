package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.version.annotation.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API 版本控制 V1 演示控制器
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/sample/version-user")
@ApiVersion("1")
public class WebVersionV1SampleController {

    /**
     * 获取用户信息 V1 版本
     *
     * @return V1 版本数据
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> getUserInfo() {
        return Result.success(Map.of(
                "version", "1",
                "name", "张三",
                "contract", "legacy"
        ));
    }
}
