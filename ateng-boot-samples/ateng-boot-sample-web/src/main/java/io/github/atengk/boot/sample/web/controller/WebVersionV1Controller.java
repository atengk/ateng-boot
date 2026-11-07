package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.vo.UserVersionInfoVO;
import io.github.atengk.boot.web.version.annotation.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员档案 API 版本控制 V1 演示控制器
 * 演示通过类级别标注 {@link ApiVersion}("1") 声明基础版本契约
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/web/routing/member")
@ApiVersion("1")
public class WebVersionV1Controller {

    /**
     * 获取会员档案 V1 基础版本信息
     *
     * @return V1 基础版本数据
     */
    @GetMapping("/info")
    public Result<UserVersionInfoVO> getUserInfo() {
        return Result.success(UserVersionInfoVO.builder()
                .version("1")
                .name("张三")
                .contract("standard_v1")
                .build());
    }
}
