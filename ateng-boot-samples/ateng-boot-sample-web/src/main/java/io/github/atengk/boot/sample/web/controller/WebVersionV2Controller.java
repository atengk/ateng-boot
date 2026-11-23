package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.vo.UserVersionInfoVO;
import io.github.atengk.boot.web.version.annotation.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员档案 API 版本控制 V2 演示控制器
 * 演示通过类级别标注 {@link ApiVersion}("2") 声明增强版本契约，以及通过方法级别标注 {@link ApiVersion}("2.1") 实施特权方法覆盖
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/web/routing/member")
@ApiVersion("2")
public class WebVersionV2Controller {

    /**
     * 获取会员档案 V2 增强版本信息（返回拓展字段）
     *
     * @return V2 增强版本数据
     */
    @GetMapping("/info")
    public Result<UserVersionInfoVO> getUserInfo() {
        return Result.success(UserVersionInfoVO.builder()
                .version("2")
                .name("张三")
                .level("VIP")
                .contract("enhanced_v2")
                .build());
    }

    /**
     * 方法级版本覆盖端点（标注 @ApiVersion("2.1") 演示方法级覆盖类级别）
     *
     * @return 2.1 专有特性数据
     */
    @GetMapping("/feature")
    @ApiVersion("2.1")
    public Result<String> getV21Feature() {
        return Result.success("feature_v2.1");
    }
}
