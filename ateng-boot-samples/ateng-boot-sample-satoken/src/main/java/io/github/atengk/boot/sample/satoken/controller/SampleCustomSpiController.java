package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态 SPI 白名单示例控制器
 * 路径属于 SampleWhitelistProvider 注册的 /api/custom-spi/**，自身无需标注 @Anonymous
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@RestController
@RequestMapping("/api/custom-spi")
public class SampleCustomSpiController {

    /**
     * SPI 白名单数据接口
     *
     * @return 业务响应
     */
    @GetMapping("/data")
    public Result<String> getData() {
        return Result.success("SPI白名单访问成功");
    }
}
