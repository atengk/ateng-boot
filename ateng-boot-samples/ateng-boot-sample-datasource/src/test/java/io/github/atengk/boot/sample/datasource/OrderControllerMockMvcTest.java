package io.github.atengk.boot.sample.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单控制器端到端 MockMvc 集成测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReadAfterWriteManager readAfterWriteManager;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        readAfterWriteManager.clear();
    }

    @Test
    @DisplayName("Web 边界端到端: 携带 X-Tenant-Id 请求头透明路由至租户库创建订单并查询")
    void testTenantOrderRestApi() throws Exception {
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderNo("REST-TENANT-1001")
                .productName("SaaS MockMvc Item")
                .amount(new BigDecimal("128.50"))
                .build();

        // 1. POST /api/orders/tenant 创建订单
        mockMvc.perform(post("/api/orders/tenant")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("REST-TENANT-1001"))
                .andExpect(jsonPath("$.data.tenantId").value("1001"));

        // 2. GET /api/orders/tenant/REST-TENANT-1001 租户1001查询成功
        mockMvc.perform(get("/api/orders/tenant/REST-TENANT-1001")
                        .header("X-Tenant-Id", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("REST-TENANT-1001"));

        // 3. 租户 1002 查不到租户 1001 的数据
        mockMvc.perform(get("/api/orders/tenant/REST-TENANT-1001")
                        .header("X-Tenant-Id", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("JSR-303 参数校验防线: 入参非法时触发快速失败，返回统一错误契约")
    void testValidationFailsFast() throws Exception {
        OrderCreateRequest illegalReq = OrderCreateRequest.builder()
                .orderNo("") // 空订单号
                .productName("Item")
                .amount(new BigDecimal("-1.00")) // 负数金额
                .build();

        mockMvc.perform(post("/api/orders/tenant")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(illegalReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("Web 边界端到端: 主库下单并激活写后读主防延迟")
    void testMasterOrderWithPostWrite() throws Exception {
        String orderNo = "REST-MASTER-001";
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderNo(orderNo)
                .productName("Master Item")
                .amount(new BigDecimal("666.00"))
                .build();

        // 1. POST /api/orders/master 下单
        mockMvc.perform(post("/api/orders/master")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // 2. GET /api/orders/read-write/{orderNo} 处于写后读主保护期内，自动回退到 master 查出数据
        mockMvc.perform(get("/api/orders/read-write/" + orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));
    }
}
