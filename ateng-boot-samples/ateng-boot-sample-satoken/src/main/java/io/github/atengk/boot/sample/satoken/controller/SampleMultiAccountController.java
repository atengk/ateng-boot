package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多账号体系 (Multi-Account / loginType) 隔离认证示例控制器
 * 演示管理端 (admin) 与移动会员端 (member) 独立登录、独立凭据与互不顶替
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
@RestController
@RequestMapping("/api/multi-account")
public class SampleMultiAccountController {

    // ==================== 管理端 (admin) 体系 ====================

    /**
     * 管理端用户登录
     *
     * @param loginUser 登录实体
     * @return 签发的 admin 体系 Token
     */
    @PostMapping("/admin/login")
    public Result<String> adminLogin(@RequestBody LoginUser loginUser) {
        LoginHelper.login("admin", loginUser);
        return Result.success(LoginHelper.getTokenValue("admin"));
    }

    /**
     * 获取当前管理端登录人资料
     *
     * @return 登录用户模型
     */
    @GetMapping("/admin/me")
    public Result<LoginUser> getAdminUser() {
        LoginHelper.checkLogin("admin");
        return Result.success(LoginHelper.getLoginUser("admin"));
    }

    /**
     * 管理端用户注销下线
     *
     * @return 成功响应
     */
    @PostMapping("/admin/logout")
    public Result<Void> adminLogout() {
        LoginHelper.logout("admin");
        return Result.success();
    }

    // ==================== 移动会员端 (member) 体系 ====================

    /**
     * 会员端用户登录
     *
     * @param loginUser 登录实体
     * @return 签发的 member 体系 Token
     */
    @PostMapping("/member/login")
    public Result<String> memberLogin(@RequestBody LoginUser loginUser) {
        LoginHelper.login("member", loginUser);
        return Result.success(LoginHelper.getTokenValue("member"));
    }

    /**
     * 获取当前会员端登录人资料
     *
     * @return 登录用户模型
     */
    @GetMapping("/member/me")
    public Result<LoginUser> getMemberUser() {
        LoginHelper.checkLogin("member");
        return Result.success(LoginHelper.getLoginUser("member"));
    }

    /**
     * 会员端用户注销下线
     *
     * @return 成功响应
     */
    @PostMapping("/member/logout")
    public Result<Void> memberLogout() {
        LoginHelper.logout("member");
        return Result.success();
    }
}
