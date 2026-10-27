package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.version.annotation.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API 版本控制 V2 演示控制器
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/sample/version-user")
@ApiVersion("2")
public class WebVersionV2SampleController {

    /**
     * 获取用户信息 V2 版本（返回增强信息）
     *
     * @return V2 版本数据
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> getUserInfo() {
        return Result.success(Map.of(
                "version", "2",
                "name", "张三",
                "level", "VIP",
                "contract", "enhanced"
        ));
    }

    /**
     * 方法级版本覆盖端点（标注 @ApiVersion("2.1") 演示方法级覆盖类级别）
     *
     * @return 2.1 版本专有数据
     */
    @GetMapping("/feature")
    @ApiVersion("2.1")
    public Result<String> getV21Feature() {
        return Result.success("feature_v2.1");
    }
}
