package io.github.atengk.boot.sample.kafka.repository;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模拟本地订单内存仓储（演示本地事务落库与状态隔离）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Repository
public class MockOrderRepository {

    private final List<String> savedOrderIds = Collections.synchronizedList(new ArrayList<>());

    public void save(String orderId) {
        savedOrderIds.add(orderId);
    }

    public boolean contains(String orderId) {
        return savedOrderIds.contains(orderId);
    }

    public List<String> findAll() {
        return Collections.unmodifiableList(savedOrderIds);
    }

    public void clear() {
        savedOrderIds.clear();
    }
}
