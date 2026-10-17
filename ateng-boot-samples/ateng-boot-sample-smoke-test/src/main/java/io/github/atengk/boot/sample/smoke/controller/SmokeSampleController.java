package io.github.atengk.boot.sample.smoke.controller;

import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全家桶冒烟测试端点控制器
 *
 * @author Ateng
 * @since 2026-10-17
 */
@RestController
@RequestMapping("/api/smoke")
public class SmokeSampleController {

    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("PONG");
    }
}
