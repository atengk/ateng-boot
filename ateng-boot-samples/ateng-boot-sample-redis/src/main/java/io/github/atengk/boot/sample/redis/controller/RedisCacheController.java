package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.redis.model.BloomFilterMetricsVO;
import io.github.atengk.boot.sample.redis.model.ProductVO;
import io.github.atengk.boot.sample.redis.model.cache.SysDictVO;
import io.github.atengk.boot.sample.redis.model.cache.UserProfileVO;
import io.github.atengk.boot.sample.redis.service.CacheScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分布式多级缓存与布隆防卫控制器
 * 演示单级独立 TTL 字典缓存、RedisCacheTemplate 防穿透/击穿/雪崩/防 N+1、L1+L2 两级协同缓存与布隆过滤器容量预警
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/cache")
@RequiredArgsConstructor
public class RedisCacheController {

    private final CacheScenarioService cacheScenarioService;

    /**
     * 单级独立空间与 TTL 字典查询（RedisCacheManager）
     *
     * @param code 字典编码
     * @return 字典详情 VO
     */
    @GetMapping("/dict/{code}")
    public Result<SysDictVO> getDict(@PathVariable("code") String code) {
        log.info("[RedisCacheController] 查询单级字典缓存: code={}", code);
        SysDictVO vo = cacheScenarioService.getSysDict(code);
        return Result.success(vo);
    }

    /**
     * 驱逐单级字典缓存
     *
     * @param code 字典编码
     * @return 成功状态
     */
    @DeleteMapping("/dict/{code}")
    public Result<Boolean> evictDict(@PathVariable("code") String code) {
        log.info("[RedisCacheController] 驱逐单级字典缓存: code={}", code);
        cacheScenarioService.evictSysDict(code);
        return Result.success(true);
    }

    /**
     * 单商品安全查询（RedisCacheTemplate 防击穿/防穿透/防雪崩）
     *
     * @param id 商品 ID
     * @return 商品详情 VO
     */
    @GetMapping("/product/{id}")
    public Result<ProductVO> getProduct(@PathVariable("id") Long id) {
        log.info("[RedisCacheController] 查询单商品缓存: id={}", id);
        ProductVO vo = cacheScenarioService.getProduct(id);
        return Result.success(vo);
    }

    /**
     * 批量商品安全查询（RedisCacheTemplate MGET + 字典序排序锁防 N+1）
     *
     * @param productIds 商品 ID 列表
     * @return 商品映射 Map
     */
    @PostMapping("/products/batch")
    public Result<Map<Long, ProductVO>> batchGetProducts(@RequestBody List<Long> productIds) {
        log.info("[RedisCacheController] 批量查询商品缓存: ids={}", productIds);
        Map<Long, ProductVO> map = cacheScenarioService.batchGetProducts(productIds);
        return Result.success(map);
    }

    /**
     * 驱逐单商品缓存
     *
     * @param id 商品 ID
     * @return 成功状态
     */
    @DeleteMapping("/product/{id}")
    public Result<Boolean> evictProduct(@PathVariable("id") Long id) {
        log.info("[RedisCacheController] 驱逐商品缓存: id={}", id);
        cacheScenarioService.evictProduct(id);
        return Result.success(true);
    }

    /**
     * 用户档案两级缓存查询（Caffeine L1 + Redis L2 协同命中与广播失效）
     *
     * @param userId 用户 ID
     * @return 用户档案 VO
     */
    @GetMapping("/user-profile/{userId}")
    public Result<UserProfileVO> getUserProfile(@PathVariable("userId") Long userId) {
        log.info("[RedisCacheController] 查询用户档案两级缓存: userId={}", userId);
        UserProfileVO vo = cacheScenarioService.getUserProfileVO(userId);
        return Result.success(vo);
    }

    /**
     * 驱逐用户档案两级缓存（L1+L2 广播淘汰）
     *
     * @param userId 用户 ID
     * @return 成功状态
     */
    @DeleteMapping("/user-profile/{userId}")
    public Result<Boolean> evictUserProfile(@PathVariable("userId") Long userId) {
        log.info("[RedisCacheController] 驱逐用户档案两级缓存: userId={}", userId);
        cacheScenarioService.evictUserProfile(userId);
        return Result.success(true);
    }

    /**
     * 添加用户至黑名单布隆过滤器
     *
     * @param userId 用户 ID
     * @return 是否添加成功
     */
    @PostMapping("/blacklist/{userId}")
    public Result<Boolean> addBlacklist(@PathVariable("userId") String userId) {
        log.info("[RedisCacheController] 添加用户至布隆黑名单: userId={}", userId);
        boolean added = cacheScenarioService.addBlacklist(userId);
        return Result.success(added);
    }

    /**
     * 判定用户是否存在于黑名单中（布隆过滤器高效拦截）
     *
     * @param userId 用户 ID
     * @return 是否在黑名单中
     */
    @GetMapping("/blacklist/check/{userId}")
    public Result<Boolean> checkBlacklist(@PathVariable("userId") String userId) {
        log.info("[RedisCacheController] 判定用户是否在布隆黑名单: userId={}", userId);
        boolean exists = cacheScenarioService.checkBlacklist(userId);
        return Result.success(exists);
    }

    /**
     * 获取黑名单布隆过滤器运行时容量指标与预警状态
     *
     * @return 指标 VO
     */
    @GetMapping("/blacklist/metrics")
    public Result<BloomFilterMetricsVO> getBlacklistMetrics() {
        BloomFilterMetricsVO metrics = cacheScenarioService.getBlacklistMetrics();
        return Result.success(metrics);
    }
}
