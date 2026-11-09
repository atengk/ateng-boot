package io.github.atengk.boot.sample.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.core.dto.CustomerOnboardingRequest;
import io.github.atengk.boot.sample.core.dto.OrderCheckRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 传输契约、因果异常与分层校验端到端 HTTP 集成测试
 * 覆盖 CoreContractController 全部 4 个对外端点契约、第一道防线 JSR-303 / @InEnum 拦截与通用枚举选项输出
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Core 传输契约与分层校验端到端集成测试")
class CoreContractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("测试订单结算前置断言成功并验证双模堆栈与状态码号段")
    void testCheckOrder_Success() throws Exception {
        OrderCheckRequest request = OrderCheckRequest.builder()
                .orderNo("ORD-20261107-9999")
                .amount(new BigDecimal("199.50"))
                .simulateDriverFailure(true)
                .build();

        mockMvc.perform(post("/sample/core/contract/order-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.orderNo", is("ORD-20261107-9999")))
                .andExpect(jsonPath("$.data.checkStatus", is("CHECKED_PASSED")))
                .andExpect(jsonPath("$.data.lightweightStackLength", is(0)))
                .andExpect(jsonPath("$.data.causalStackLength", notNullValue()))
                .andExpect(jsonPath("$.data.resultCodeSegment", is("SYSTEM_CORE")));
    }

    @Test
    @DisplayName("测试订单结算校验入参为空触发 JSR-303 格式校验拦截并返回 400")
    void testCheckOrder_ValidationError() throws Exception {
        OrderCheckRequest invalidRequest = OrderCheckRequest.builder()
                .orderNo("")
                .amount(null)
                .build();

        mockMvc.perform(post("/sample/core/contract/order-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("测试订单分页查询参数约束与结果解耦")
    void testQueryOrdersPage() throws Exception {
        mockMvc.perform(get("/sample/core/contract/orders/page")
                        .param("current", "1")
                        .param("size", "10")
                        .param("sorts[0].property", "orderTime")
                        .param("sorts[0].direction", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.current", is("1")))
                .andExpect(jsonPath("$.data.size", is("10")))
                .andExpect(jsonPath("$.data.total", is("100")))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].orderNo", is("ORD-20261107-1001")))
                .andExpect(jsonPath("$.data.records[0].statusDesc", is("已支付")));
    }

    @Test
    @DisplayName("测试 ValidList 批量客户入驻校验成功")
    void testBatchValidateCustomers_Success() throws Exception {
        List<CustomerOnboardingRequest> customers = List.of(
                CustomerOnboardingRequest.builder()
                        .name("张三")
                        .phone("13812345678")
                        .statusCode(1)
                        .build(),
                CustomerOnboardingRequest.builder()
                        .name("李四")
                        .phone("13987654321")
                        .statusCode(2)
                        .build()
        );
        ValidList<CustomerOnboardingRequest> validList = new ValidList<>(customers);

        mockMvc.perform(post("/sample/core/contract/users/batch-validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validList)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", is(2)));
    }

    @Test
    @DisplayName("测试 ValidList 级联校验与 @InEnum 拦截非法状态枚举入参")
    void testBatchValidateCustomers_InvalidEnum() throws Exception {
        List<CustomerOnboardingRequest> customers = List.of(
                CustomerOnboardingRequest.builder()
                        .name("王五")
                        .phone("13800000000")
                        .statusCode(999) // 999 不属于 OrderStatusEnum 合法枚举值
                        .build()
        );
        ValidList<CustomerOnboardingRequest> validList = new ValidList<>(customers);

        mockMvc.perform(post("/sample/core/contract/users/batch-validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validList)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("测试通用字典枚举转换为前端标准 OptionItem 选项列表契约")
    void testGetOrderStatusOptions() throws Exception {
        mockMvc.perform(get("/sample/core/contract/enums/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", hasSize(5)))
                .andExpect(jsonPath("$.data[0].code", is(1)))
                .andExpect(jsonPath("$.data[0].label", is("待支付")))
                .andExpect(jsonPath("$.data[0].disabled", is(false)))
                .andExpect(jsonPath("$.data[4].code", is(5)))
                .andExpect(jsonPath("$.data[4].label", is("已取消")))
                .andExpect(jsonPath("$.data[4].disabled", is(true)))
                .andExpect(jsonPath("$.data[4].extra.badge", is("danger")));
    }

    @Test
    @DisplayName("测试编程式分页纠偏与防溢出偏移量计算 (非法入参纠偏)")
    void testNormalizePage_WithIllegalInputs() throws Exception {
        mockMvc.perform(get("/sample/core/contract/page-normalize")
                        .param("current", "0")
                        .param("size", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.rawCurrent", is("0")))
                .andExpect(jsonPath("$.data.rawSize", is("-5")))
                .andExpect(jsonPath("$.data.safeCurrent", is("1")))
                .andExpect(jsonPath("$.data.safeSize", is("10")))
                .andExpect(jsonPath("$.data.offset", is("0")))
                .andExpect(jsonPath("$.data.normalizedCurrent", is("1")))
                .andExpect(jsonPath("$.data.normalizedSize", is("10")));
    }

    @Test
    @DisplayName("测试编程式分页纠偏与防溢出偏移量计算 (合法入参偏移量计算)")
    void testNormalizePage_WithValidInputs() throws Exception {
        mockMvc.perform(get("/sample/core/contract/page-normalize")
                        .param("current", "3")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.rawCurrent", is("3")))
                .andExpect(jsonPath("$.data.rawSize", is("20")))
                .andExpect(jsonPath("$.data.safeCurrent", is("3")))
                .andExpect(jsonPath("$.data.safeSize", is("20")))
                .andExpect(jsonPath("$.data.offset", is("40")))
                .andExpect(jsonPath("$.data.normalizedCurrent", is("3")))
                .andExpect(jsonPath("$.data.normalizedSize", is("20")));
    }
}
