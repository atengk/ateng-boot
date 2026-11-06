package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.core.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约目标服务模拟控制器 (用于本地运行与端到端真实联调)
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
public class MockOrderApiController {

    /**
     * 根据订单ID查询订单详情
     */
    @GetMapping("/{id}")
    public Map<String, Object> getOrderById(
            @PathVariable("id") Long id,
            @RequestParam(value = "includeItems", required = false, defaultValue = "false") Boolean includeItems) {
        log.info("[MOCK-ORDER-API] 收到查询订单详情请求: id={}, includeItems={}", id, includeItems);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", id);
        order.put("buyerName", "张三");
        order.put("amount", 299.0);
        order.put("status", "PAID");
        order.put("channel", "ONLINE");
        if (Boolean.TRUE.equals(includeItems)) {
            order.put("items", List.of(
                    Map.of("skuId", 8001, "skuName", "智能无线降噪耳机", "qty", 1, "price", 299.0)
            ));
        }
        return order;
    }

    /**
     * 分页查询订单列表
     */
    @GetMapping
    public List<Map<String, Object>> listOrders(
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "status", required = false) String status) {
        log.info("[MOCK-ORDER-API] 收到分页查询订单列表请求: page={}, status={}", page, status);
        Map<String, Object> order1 = new LinkedHashMap<>();
        order1.put("id", 1001L);
        order1.put("buyerName", "张三");
        order1.put("amount", 299.0);
        order1.put("status", status != null ? status : "PAID");

        Map<String, Object> order2 = new LinkedHashMap<>();
        order2.put("id", 1002L);
        order2.put("buyerName", "李四");
        order2.put("amount", 88.5);
        order2.put("status", status != null ? status : "SHIPPED");

        return List.of(order1, order2);
    }

    /**
     * 创建商品订单
     */
    @PostMapping
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> payload) {
        log.info("[MOCK-ORDER-API] 收到创建订单请求: payload={}", payload);
        Map<String, Object> created = new LinkedHashMap<>(payload);
        created.put("id", 1003L);
        created.put("status", "CREATED");
        return created;
    }

    /**
     * 取消或作废订单
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> cancelOrder(@PathVariable("id") Long id) {
        log.info("[MOCK-ORDER-API] 收到作废订单请求: id={}", id);
        Map<String, Object> cancelled = new LinkedHashMap<>();
        cancelled.put("id", id);
        cancelled.put("status", "CANCELLED");
        return cancelled;
    }
}
