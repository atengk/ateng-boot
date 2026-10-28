package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 类级别声明式匿名公开访问示例控制器
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@RestController
@RequestMapping("/api/anonymous")
@Anonymous
public class SampleAnonymousController {

    /**
     * 类级匿名欢迎接口
     *
     * @return 响应结果
     */
    @GetMapping("/hello")
    public Result<String> hello() {
        return Result.success("类级匿名访问成功");
    }

    /**
     * 类级匿名带路径参数接口
     *
     * @param itemId 业务标识
     * @return 响应结果
     */
    @GetMapping("/item/{itemId}")
    public Result<String> getItem(@PathVariable("itemId") String itemId) {
        return Result.success("类级匿名条目详情: " + itemId);
    }
}
