package io.github.atengk.boot.sample.satoken.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.provider.SampleDynamicPathPermissionProvider;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.satoken.model.PathPermissionRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开商城门户与动态路径权限示例控制器
 * 演示类级 @Anonymous 声明式免鉴权、方法级 @Anonymous(false) 翻转保护、
 * SaTokenWhitelistProvider SPI 动态白名单以及 DynamicPathPermissionProvider 动态路由权限治理
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@RestController
@RequestMapping("/sample/satoken/portal")
@Anonymous
@RequiredArgsConstructor
public class PublicPortalController {

    private final SampleDynamicPathPermissionProvider dynamicPathPermissionProvider;

    /**
     * 公开商城广播公告（类级 @Anonymous 放行）
     *
     * @return 广播公告内容
     */
    @GetMapping("/announcement")
    public Result<String> getAnnouncement() {
        return Result.success("【系统公告】双十一全场云原生基础设施 Starter 限时特惠开启！");
    }

    /**
     * 公开商品详情（类级 @Anonymous 放行，支持路径变量通配）
     *
     * @param productId 商品标识
     * @return 商品详情
     */
    @GetMapping("/products/{productId}")
    public Result<Map<String, Object>> getProductDetail(@PathVariable("productId") String productId) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productId", productId);
        product.put("title", "Spring Boot 3 企业级微服务开发实战");
        product.put("price", new BigDecimal("99.00"));
        product.put("stock", 500);
        return Result.success(product);
    }

    /**
     * VIP 专属底价查询（方法级 @Anonymous(false) 强制翻转拦截，需认证登录）
     *
     * @param productId 商品标识
     * @return VIP 专属价格信息
     */
    @GetMapping("/products/{productId}/vip-price")
    @Anonymous(false)
    @SaCheckLogin
    public Result<Map<String, Object>> getVipPrice(@PathVariable("productId") String productId) {
        Map<String, Object> vipPrice = new LinkedHashMap<>();
        vipPrice.put("productId", productId);
        vipPrice.put("originalPrice", new BigDecimal("99.00"));
        vipPrice.put("vipPrice", new BigDecimal("69.00"));
        vipPrice.put("vipDiscount", "70%");
        return Result.success(vipPrice);
    }

    /**
     * 开放商品类目接口（方法级 @Anonymous(false)，由 SampleWhitelistProvider SPI 动态注册放行）
     *
     * @return 商品类目列表
     */
    @GetMapping("/categories")
    @Anonymous(false)
    public Result<List<String>> getCategories() {
        return Result.success(List.of("云计算", "中间件", "数据库", "开发框架"));
    }

    /**
     * 动态权限接口：每日运营报表查询（方法级 @Anonymous(false)，受 DynamicPathPermissionProvider 保护：portal:report:view）
     *
     * @return 报表详情
     */
    @GetMapping("/reports/daily")
    @Anonymous(false)
    public Result<String> getDailyReport() {
        return Result.success("每日运营分析报表数据汇总");
    }

    /**
     * 动态权限接口：每日运营报表导出（方法级 @Anonymous(false)，受 DynamicPathPermissionProvider 保护：portal:report:export）
     *
     * @return 导出结果
     */
    @PostMapping("/reports/daily")
    @Anonymous(false)
    public Result<String> exportDailyReport() {
        return Result.success("每日运营分析报表导出任务已提交");
    }

    /**
     * 动态权限接口：每日运营历史报表彻底销毁（方法级 @Anonymous(false)，受 DynamicPathPermissionProvider 保护：portal:report:delete AND portal:report:audit）
     *
     * @return 删除结果
     */
    @DeleteMapping("/reports/daily")
    @Anonymous(false)
    public Result<String> deleteDailyReport() {
        return Result.success("每日运营历史归档报表已彻底销毁");
    }

    /**
     * 动态权限无约束接口（方法级 @Anonymous(false)，未配置动态规则，非超级管理员有登录态亦可放行）
     *
     * @return 响应结果
     */
    @GetMapping("/reports/unprotected")
    @Anonymous(false)
    public Result<String> getUnprotectedReport() {
        return Result.success("无约束报表数据公开汇总");
    }

    /**
     * 查看当前生效的动态路径权限规则列表
     *
     * @return 规则列表
     */
    @GetMapping("/rules")
    public Result<List<PathPermissionRule>> getRules() {
        return Result.success(dynamicPathPermissionProvider.getPathPermissionRules());
    }

    /**
     * 运行时热刷新动态路径权限规则
     *
     * @return 操作结果
     */
    @PostMapping("/rules/refresh")
    public Result<Void> refreshRules() {
        dynamicPathPermissionProvider.refresh();
        return Result.success();
    }
}
