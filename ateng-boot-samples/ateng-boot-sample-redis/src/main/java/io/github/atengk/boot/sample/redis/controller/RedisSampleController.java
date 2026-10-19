package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.redis.model.ProductVO;
import io.github.atengk.boot.sample.redis.service.OrderDelayQueueService;
import io.github.atengk.boot.sample.redis.service.RedisSampleService;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 场景样例控制器
 * 演示声明式分布式锁、接口限流防刷、幂等防重、延时队列投递与原子发号（零数据库依赖）
 *
 * @author Ateng
 * @since 2026-10-17
 */
@RestController
@RequestMapping("/api/redis-sample")
@RequiredArgsConstructor
public class RedisSampleController {

    private final RedisSampleService redisSampleService;
    private final OrderDelayQueueService orderDelayQueueService;
    private final io.github.atengk.boot.sample.redis.service.NoticeBroadcastService noticeBroadcastService;
    private final io.github.atengk.boot.redis.bloom.RedisBloomFilter redisBloomFilter;

    /**
     * 扣减商品库存（受 Redisson 分布式锁严格并发保护）
     *
     * @param productId 商品 ID
     * @param count     扣减数量
     * @return 业务响应
     */
    @PostMapping("/deduct-stock")
    public Result<String> deductStock(@RequestParam("productId") Long productId,
                                      @RequestParam("count") Integer count) {
        return Result.success(redisSampleService.deductStock(productId, count));
    }

    /**
     * 接口限流防刷演示
     *
     * @param userId 用户 ID
     * @return 成功响应
     */
    @GetMapping("/limited-api")
    @RateLimiter(key = "'sample_limit:' + #userId", count = 5, time = 1)
    public Result<String> limitedApi(@RequestParam("userId") String userId) {
        return Result.success("请求成功: userId=" + userId);
    }

    /**
     * 接口幂等防重演示
     *
     * @param token 幂等令牌
     * @return 成功响应
     */
    @PostMapping("/idempotent")
    @Idempotent(key = "'sample_idempotent:' + #token", timeout = 10)
    public Result<String> idempotentApi(@RequestParam("token") String token) {
        return Result.success("幂等提交成功: token=" + token);
    }

    /**
     * 投递订单延时取消任务
     *
     * @param orderId      订单标识
     * @param delaySeconds 延迟秒数
     * @return 投递成功响应
     */
    @PostMapping("/delay-order")
    public Result<String> sendDelayOrder(@RequestParam("orderId") String orderId,
                                         @RequestParam(value = "delaySeconds", defaultValue = "30") Long delaySeconds) {
        orderDelayQueueService.sendOrderCancelTask(orderId, Duration.ofSeconds(delaySeconds));
        return Result.success("延时取消订单任务投递成功: " + orderId);
    }

    /**
     * 安全游标扫描匹配的 Key 集合
     *
     * @param pattern 模糊匹配模式
     * @param count   单次批次数量
     * @return 匹配结果
     */
    @GetMapping("/scan-keys")
    public Result<Set<String>> scanKeys(@RequestParam("pattern") String pattern,
                                        @RequestParam(value = "count", defaultValue = "100") Long count) {
        return Result.success(redisSampleService.scanKeys(pattern, count));
    }

    /**
     * 生成按日自重置的结构化原子订单号
     *
     * @param prefix 前缀
     * @param length 位宽
     * @return 生成的流水号
     */
    @GetMapping("/daily-seq")
    public Result<String> dailySeq(@RequestParam(value = "prefix", defaultValue = "ORD") String prefix,
                                   @RequestParam(value = "length", defaultValue = "6") Integer length) {
        return Result.success(redisSampleService.generateDailyOrderNo(prefix, length));
    }

    /**
     * 广播发布系统公告
     *
     * @param noticeId 公告 ID
     * @param title    公告标题
     * @param content  公告内容
     * @return 接收订阅数
     */
    @PostMapping("/broadcast-notice")
    public Result<Long> broadcastNotice(@RequestParam("noticeId") String noticeId,
                                        @RequestParam("title") String title,
                                        @RequestParam("content") String content) {
        io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload payload =
                new io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload(noticeId, title, content, System.currentTimeMillis());
        return Result.success(noticeBroadcastService.publishNotice(payload));
    }

    /**
     * 获取已接收的公告列表
     *
     * @return 公告列表
     */
    @GetMapping("/received-notices")
    public Result<java.util.List<io.github.atengk.boot.sample.redis.model.NoticeBroadcastPayload>> getReceivedNotices() {
        return Result.success(noticeBroadcastService.getReceivedNotices());
    }

    /**
     * 广播发布系统通配事件
     *
     * @param eventType 事件类型
     * @param data      事件数据
     * @return 接收订阅数
     */
    @PostMapping("/broadcast-system-event")
    public Result<Long> broadcastSystemEvent(@RequestParam("eventType") String eventType,
                                            @RequestParam("data") String data) {
        return Result.success(noticeBroadcastService.publishSystemEvent(eventType, data));
    }

    /**
     * 获取已接收的系统事件列表
     *
     * @return 事件列表
     */
    @GetMapping("/received-system-events")
    public Result<java.util.List<String>> getReceivedSystemEvents() {
        return Result.success(noticeBroadcastService.getReceivedSystemEvents());
    }

    /**
     * 将用户添加至黑名单布隆过滤器
     *
     * @param userId 用户 ID
     * @return 是否添加成功
     */
    @PostMapping("/bloom/blacklist/add")
    public Result<Boolean> addBlacklist(@RequestParam("userId") String userId) {
        boolean added = io.github.atengk.boot.redis.bloom.RedisBloomFilter.add("user-blacklist", userId);
        return Result.success(added);
    }

    /**
     * 判定用户是否可能处于黑名单中
     *
     * @param userId 用户 ID
     * @return 是否处于黑名单
     */
    @GetMapping("/bloom/blacklist/check")
    public Result<Boolean> checkBlacklist(@RequestParam("userId") String userId) {
        boolean inBlacklist = io.github.atengk.boot.redis.bloom.RedisBloomFilter.contains("user-blacklist", userId);
        return Result.success(inBlacklist);
    }

    /**
     * 查询黑名单布隆过滤器的监控指标
     *
     * @return 监控指标
     */
    @GetMapping("/bloom/blacklist/metrics")
    public Result<java.util.Map<String, Object>> getBlacklistMetrics() {
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();
        metrics.put("filterName", "user-blacklist");
        metrics.put("count", io.github.atengk.boot.redis.bloom.RedisBloomFilter.count("user-blacklist"));
        metrics.put("expectedInsertions", io.github.atengk.boot.redis.bloom.RedisBloomFilter.getExpectedInsertions("user-blacklist"));
        metrics.put("usageRate", io.github.atengk.boot.redis.bloom.RedisBloomFilter.getUsageRate("user-blacklist"));
        metrics.put("falseProbability", io.github.atengk.boot.redis.bloom.RedisBloomFilter.getFalseProbability("user-blacklist"));
        return Result.success(metrics);
    }

    /**
     * 单商品安全查询（通过 RedisCacheTemplate 防穿透、防击穿、防雪崩）
     *
     * @param productId 商品 ID
     * @return 商品详情统一响应
     */
    @GetMapping("/cache/product")
    public Result<ProductVO> getProduct(@RequestParam("productId") Long productId) {
        return Result.success(redisSampleService.getProduct(productId));
    }

    /**
     * 批量商品安全查询（通过 RedisCacheTemplate MGET + 排序锁 + 批量加载防 N+1）
     *
     * @param productIds 商品 ID 列表
     * @return 商品映射 Map 统一响应
     */
    @GetMapping("/cache/products")
    public Result<Map<Long, ProductVO>> batchGetProducts(@RequestParam("productIds") List<Long> productIds) {
        return Result.success(redisSampleService.batchGetProducts(productIds));
    }

    /**
     * 清理指定商品缓存
     *
     * @param productId 商品 ID
     * @return 成功响应
     */
    @PostMapping("/cache/product/evict")
    public Result<Void> evictProduct(@RequestParam("productId") Long productId) {
        redisSampleService.evictProduct(productId);
        return Result.success();
    }

    /**
     * 查询用户档案（基于 Spring Cache @Cacheable 两级缓存）
     *
     * @param userId 用户 ID
     * @return 角色档案
     */
    @GetMapping("/cache/user-profile")
    public Result<String> getUserProfile(@RequestParam("userId") Long userId) {
        return Result.success(redisSampleService.getUserProfile(userId));
    }

    /**
     * 淘汰指定用户档案两级缓存（基于 Spring Cache @CacheEvict）
     *
     * @param userId 用户 ID
     * @return 成功响应
     */
    @PostMapping("/cache/user-profile/evict")
    public Result<Void> evictUserProfile(@RequestParam("userId") Long userId) {
        redisSampleService.evictUserProfile(userId);
        return Result.success();
    }

    /**
     * 清空用户档案全量两级缓存（基于 Spring Cache @CacheEvict allEntries = true）
     *
     * @return 成功响应
     */
    @PostMapping("/cache/user-profile/clear")
    public Result<Void> clearUserProfile() {
        redisSampleService.clearUserProfileCache();
        return Result.success();
    }
}
