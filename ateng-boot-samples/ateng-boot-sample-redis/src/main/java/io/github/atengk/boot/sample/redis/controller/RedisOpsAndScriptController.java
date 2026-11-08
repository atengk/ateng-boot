package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.sample.redis.model.ops.*;
import io.github.atengk.boot.sample.redis.service.ScriptExecutionScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 基础数据操作、Lua 脚本与原子序列控制器
 * 演示 Hash 购物车、ZSet 热销榜、游标 SCAN、Pipeline 批量预热、受管 Lua 优惠券扣减与跨天自重置流水号
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/ops")
@RequiredArgsConstructor
public class RedisOpsAndScriptController {

    private final ScriptExecutionScenarioService scriptExecutionScenarioService;

    private static final String CART_PREFIX = "sample:cart:";
    private static final String RANK_KEY = "sample:rank:hot-products";
    private static final String STOCK_PREFIX = "sample:stock:";
    private static final String COUPON_STOCK_PREFIX = "sample:coupon:stock:";

    /**
     * 1. 购物车：添加或更新商品明细 (Hash 结构)
     */
    @PostMapping("/cart/items")
    public Result<CartVO> addCartItem(@RequestBody @Valid CartItemRequest request) {
        String cartKey = CART_PREFIX + request.getUserId();
        BigDecimal subtotal = request.getPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
        CartItemVO item = CartItemVO.builder()
                .skuId(request.getSkuId())
                .skuName(request.getSkuName())
                .quantity(request.getQuantity())
                .price(request.getPrice())
                .subtotal(subtotal)
                .build();

        RedisUtil.hSet(cartKey, request.getSkuId(), item);
        return Result.success(buildCartVO(request.getUserId(), cartKey));
    }

    /**
     * 2. 购物车：查询用户聚合购物车 (Hash 结构)
     */
    @GetMapping("/cart/{userId}")
    public Result<CartVO> getCart(@PathVariable String userId) {
        String cartKey = CART_PREFIX + userId;
        return Result.success(buildCartVO(userId, cartKey));
    }

    /**
     * 3. 购物车：移除指定 SKU 项
     */
    @DeleteMapping("/cart/{userId}/items/{skuId}")
    public Result<Boolean> removeCartItem(@PathVariable String userId, @PathVariable String skuId) {
        String cartKey = CART_PREFIX + userId;
        long removed = RedisUtil.hDel(cartKey, skuId);
        return Result.success(removed > 0);
    }

    /**
     * 4. 热销榜：累加商品销量热度 (ZSet 结构)
     */
    @PostMapping("/rank/score")
    public Result<Double> addProductScore(@RequestBody @Valid ProductScoreRequest request) {
        String member = request.getProductId() + ":" + request.getProductName();
        Double current = RedisUtil.zScore(RANK_KEY, member);
        double newScore = (current != null ? current : 0.0) + request.getDeltaScore();
        RedisUtil.zAdd(RANK_KEY, member, newScore);
        return Result.success(newScore);
    }

    /**
     * 5. 热销榜：查询 Top N 热销榜单 (ZSet 结构)
     */
    @GetMapping("/rank/top")
    public Result<List<ProductRankVO>> getTopProducts(@RequestParam(defaultValue = "10") int limit) {
        Set<ZSetOperations.TypedTuple<Object>> tupleSet = RedisUtil.zRangeWithScores(RANK_KEY, 0, limit - 1);
        List<ProductRankVO> ranks = new ArrayList<>();
        if (tupleSet != null) {
            int rankIndex = 1;
            for (ZSetOperations.TypedTuple<Object> tuple : tupleSet) {
                String member = String.valueOf(tuple.getValue());
                String[] parts = member.split(":", 2);
                String productId = parts[0];
                String productName = parts.length > 1 ? parts[1] : productId;
                Double score = tuple.getScore();
                ranks.add(ProductRankVO.builder()
                        .rank(rankIndex++)
                        .productId(productId)
                        .productName(productName)
                        .score(score)
                        .build());
            }
        }
        return Result.success(ranks);
    }

    /**
     * 6. 游标扫描：基于 SCAN 安全巡检匹配的键集合
     */
    @GetMapping("/keys/scan")
    public Result<ScanResultVO> scanKeys(@RequestParam(defaultValue = "sample:*") String pattern,
                                         @RequestParam(defaultValue = "100") long count) {
        Set<String> matched = scriptExecutionScenarioService.scanKeys(pattern, count);
        return Result.success(ScanResultVO.builder()
                .pattern(pattern)
                .count(count)
                .matchedKeys(matched)
                .totalMatched(matched.size())
                .build());
    }

    /**
     * 7. Pipeline：批量库存预热
     */
    @PostMapping("/pipeline/warmup")
    public Result<PipelineWarmupVO> warmupStock(@RequestBody @Valid PipelineWarmupRequest request) {
        long start = System.currentTimeMillis();
        Map<String, Integer> stockMap = request.getStockMap();
        RedisUtil.executePipelined(ops -> {
            stockMap.forEach((skuId, stock) -> {
                String key = STOCK_PREFIX + skuId;
                ops.opsForValue().set(key, stock);
            });
        });
        long elapsed = System.currentTimeMillis() - start;
        return Result.success(PipelineWarmupVO.builder()
                .totalItems(stockMap.size())
                .elapsedMs(elapsed)
                .success(true)
                .build());
    }

    /**
     * 8. 受管 Lua：执行 coupon_deduct.lua 优惠券原子核销
     */
    @PostMapping("/coupon/deduct")
    public Result<CouponDeductVO> deductCoupon(@RequestBody @Valid CouponDeductRequest request) {
        String couponKey = COUPON_STOCK_PREFIX + request.getCouponId();
        // 若库存未预热，初始赋 100 默认库存
        if (RedisUtil.get(couponKey) == null) {
            RedisUtil.set(couponKey, 100L);
        }

        Long remaining = scriptExecutionScenarioService.executeClasspathScript(
                "scripts/coupon_deduct.lua",
                Long.class,
                List.of(couponKey),
                request.getDeductCount()
        );

        boolean success = remaining != null && remaining >= 0;
        return Result.success(CouponDeductVO.builder()
                .couponId(request.getCouponId())
                .deductCount(request.getDeductCount())
                .remainingStock(remaining)
                .success(success)
                .message(success ? "优惠券核销成功" : "优惠券库存不足，核销失败")
                .build());
    }

    /**
     * 9. 原子发号：生成跨天自重置原子订单流水号
     */
    @PostMapping("/seq/daily")
    public Result<SequenceVO> generateDailyOrderNo(@RequestParam(defaultValue = "ORD") String prefix,
                                                   @RequestParam(defaultValue = "6") int width) {
        String fullOrderNo = scriptExecutionScenarioService.generateDailyOrderNo(prefix, width);
        String todayStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String seqPart = fullOrderNo.substring(prefix.length() + todayStr.length());
        return Result.success(SequenceVO.builder()
                .prefix(prefix)
                .date(todayStr)
                .sequence(seqPart)
                .fullOrderNo(fullOrderNo)
                .build());
    }

    /**
     * 10. 水位控制：CAS 原子比较替换
     */
    @PostMapping("/watermark/cas")
    public Result<WatermarkUpdateVO> casWatermark(@RequestBody @Valid WatermarkCasRequest request) {
        boolean success = scriptExecutionScenarioService.compareAndSet(request.getKey(), request.getExpect(), request.getUpdate());
        Object current = RedisUtil.get(request.getKey());
        Long currentVal = current instanceof Number n ? n.longValue() : (current != null ? Long.parseLong(String.valueOf(current)) : null);
        return Result.success(WatermarkUpdateVO.builder()
                .key(request.getKey())
                .expect(request.getExpect())
                .update(request.getUpdate())
                .success(success)
                .currentVal(currentVal)
                .build());
    }

    private CartVO buildCartVO(String userId, String cartKey) {
        Map<Object, Object> rawEntries = RedisUtil.hGetAll(cartKey);
        List<CartItemVO> items = new ArrayList<>();
        int totalQty = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        if (rawEntries != null) {
            for (Object val : rawEntries.values()) {
                if (val instanceof CartItemVO item) {
                    items.add(item);
                    totalQty += item.getQuantity();
                    totalAmount = totalAmount.add(item.getSubtotal());
                } else if (val instanceof Map map) {
                    String skuId = String.valueOf(map.get("skuId"));
                    String skuName = String.valueOf(map.get("skuName"));
                    int qty = map.get("quantity") instanceof Number n ? n.intValue() : 1;
                    BigDecimal price = new BigDecimal(String.valueOf(map.get("price")));
                    BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty));
                    CartItemVO item = CartItemVO.builder()
                            .skuId(skuId)
                            .skuName(skuName)
                            .quantity(qty)
                            .price(price)
                            .subtotal(subtotal)
                            .build();
                    items.add(item);
                    totalQty += qty;
                    totalAmount = totalAmount.add(subtotal);
                }
            }
        }

        return CartVO.builder()
                .userId(userId)
                .items(items)
                .totalQuantity(totalQty)
                .totalPrice(totalAmount)
                .build();
    }
}
