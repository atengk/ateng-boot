package io.github.atengk.boot.sample.satoken.provider;

import io.github.atengk.boot.satoken.spi.DataOwnerProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例订单数据所有者反查 SPI 提供者
 * 模拟根据订单 ID 查库获取订单所有者的用户标识
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Component
public class SampleOrderDataOwnerProvider implements DataOwnerProvider<Long> {

    private static final Map<Long, String> MOCK_ORDER_OWNER_DB = Map.of(
            88001L, "10086",
            88002L, "20001"
    );

    @Override
    public String getOwnerId(Long resourceId) {
        return MOCK_ORDER_OWNER_DB.get(resourceId);
    }
}
