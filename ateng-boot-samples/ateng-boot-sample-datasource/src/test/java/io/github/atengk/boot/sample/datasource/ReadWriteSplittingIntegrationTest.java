package io.github.atengk.boot.sample.datasource;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读写分离与写后读主防延迟端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootTest
class ReadWriteSplittingIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReadAfterWriteManager readAfterWriteManager;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        readAfterWriteManager.clear();
    }

    @Test
    @DisplayName("读写分离与写后读主: 写入master并标记写后读主，在防延迟期内只读查询自动回退至master")
    void testPostWriteReadMasterRouting() {
        String orderNo = "ORD-RW-MASTER-001";

        // 1. 在 master 库创建订单，写事务自动激活写后读主
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderNo(orderNo)
                .productName("Read-Write Splitting Item")
                .amount(new BigDecimal("888.88"))
                .build();
        OrderResponse createRes = orderService.createMasterOrderWithPostWrite(req);
        assertThat(createRes).isNotNull();

        // 2. 验证写标记已激活
        assertThat(readAfterWriteManager.isWriteOccurred()).isTrue();

        // 3. 调用只读事务查询：由于写后读主生效，透明重定向至 master，因此可以查到该订单
        OrderResponse readRes = orderService.getOrderReadWrite(orderNo);
        assertThat(readRes).isNotNull();
        assertThat(readRes.getOrderNo()).isEqualTo(orderNo);

        // 4. 清理写标记
        readAfterWriteManager.clear();
        assertThat(readAfterWriteManager.isWriteOccurred()).isFalse();

        // 5. 再次调用只读事务查询：自动路由到 slave 读从库，由于 slave 库未同步 master 数据，此时读取返回 null
        OrderResponse slaveReadRes = orderService.getOrderReadWrite(orderNo);
        assertThat(slaveReadRes).isNull();
    }
}
