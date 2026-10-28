package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.web.dto.WebEnumUserRequest;
import io.github.atengk.boot.sample.web.enums.OrderTypeEnum;
import io.github.atengk.boot.sample.web.enums.UserStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 枚举字典与强类型绑定端到端集成测试
 * 覆盖三阶 HTTP 端点、@InEnum 第一道防线校验与 Jackson 强类型双向反序列化
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("枚举字典与强类型绑定端到端集成测试")
class WebEnumSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /api/system/enums 查询全量枚举字典目录与选项")
    void shouldGetAllEnumCatalog() throws Exception {
        mockMvc.perform(get("/api/system/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.userStatus").exists())
                .andExpect(jsonPath("$.data.orderType").exists())
                .andExpect(jsonPath("$.data.userStatus", hasSize(3)))
                .andExpect(jsonPath("$.data.userStatus[0].code", is(1)))
                .andExpect(jsonPath("$.data.userStatus[0].label", is("启用")))
                .andExpect(jsonPath("$.data.userStatus[0].disabled", is(false)))
                .andExpect(jsonPath("$.data.userStatus[1].code", is(2)))
                .andExpect(jsonPath("$.data.userStatus[1].label", is("禁用")))
                .andExpect(jsonPath("$.data.userStatus[1].disabled", is(true)))
                .andExpect(jsonPath("$.data.userStatus[1].extra.color", is("red")))
                .andExpect(jsonPath("$.data.orderType[0].code", is("normal")))
                .andExpect(jsonPath("$.data.orderType[0].label", is("普通订单")));
    }

    @Test
    @DisplayName("GET /api/system/enums/{name} 根据别名查询单个枚举字典")
    void shouldGetSingleEnumOptions() throws Exception {
        mockMvc.perform(get("/api/system/enums/userStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].code", is(1)))
                .andExpect(jsonPath("$.data[0].label", is("启用")))
                .andExpect(jsonPath("$.data[2].code", is(3)))
                .andExpect(jsonPath("$.data[2].label", is("锁定")));
    }

    @Test
    @DisplayName("POST /api/system/enums/batch 批量聚合查询多个枚举字典")
    void shouldGetBatchEnumOptionsViaPost() throws Exception {
        List<String> names = List.of("userStatus", "orderType");
        mockMvc.perform(post("/api/system/enums/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(names)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.userStatus", hasSize(3)))
                .andExpect(jsonPath("$.data.orderType", hasSize(3)));
    }

    @Test
    @DisplayName("GET /api/system/enums/batch Query 传参批量聚合查询多个枚举字典")
    void shouldGetBatchEnumOptionsViaGet() throws Exception {
        mockMvc.perform(get("/api/system/enums/batch")
                        .param("names", "userStatus,orderType"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.userStatus", hasSize(3)))
                .andExpect(jsonPath("$.data.orderType", hasSize(3)));
    }

    @Test
    @DisplayName("GET /api/system/enums/{name} 查询未匹配别名返回 404 业务错误")
    void shouldReturn404WhenEnumNotFound() throws Exception {
        mockMvc.perform(get("/api/system/enums/nonExistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(404)))
                .andExpect(jsonPath("$.msg", is("未找到名称为 [nonExistent] 的枚举字典")));
    }

    @Test
    @DisplayName("POST /api/sample/enum/user 数字 code 正确反序列化为强类型枚举")
    void shouldDeserializeNumericCodeIntoEnumField() throws Exception {
        String json = "{\"username\": \"张三\", \"statusCode\": 1, \"status\": 1, \"orderType\": \"normal\"}";
        mockMvc.perform(post("/api/sample/enum/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.username", is("张三")))
                .andExpect(jsonPath("$.data.statusCode", is(1)))
                .andExpect(jsonPath("$.data.status", is("ACTIVE")))
                .andExpect(jsonPath("$.data.orderType", is("NORMAL")));
    }

    @Test
    @DisplayName("POST /api/sample/enum/user 字符串 code 正确反序列化为强类型枚举")
    void shouldDeserializeStringCodeIntoEnumField() throws Exception {
        String json = "{\"username\": \"李四\", \"statusCode\": 1, \"status\": \"1\", \"orderType\": \"seckill\"}";
        mockMvc.perform(post("/api/sample/enum/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.status", is("ACTIVE")))
                .andExpect(jsonPath("$.data.orderType", is("SECKILL")));
    }

    @Test
    @DisplayName("POST /api/sample/enum/user 常量名称正确反序列化为强类型枚举")
    void shouldDeserializeConstantNameIntoEnumField() throws Exception {
        String json = "{\"username\": \"王五\", \"statusCode\": 2, \"status\": \"ACTIVE\", \"orderType\": \"PROMOTION\"}";
        mockMvc.perform(post("/api/sample/enum/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.status", is("ACTIVE")))
                .andExpect(jsonPath("$.data.orderType", is("PROMOTION")));
    }

    @Test
    @DisplayName("POST /api/sample/enum/user 触发 @InEnum 校验防线拦截非法编码并返回 400 错误")
    void shouldFailWhenInEnumValidationFails() throws Exception {
        String json = "{\"username\": \"赵六\", \"statusCode\": 999, \"status\": 1, \"orderType\": \"normal\"}";
        mockMvc.perform(post("/api/sample/enum/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.msg", is("用户状态编码不合法")));
    }

    @Test
    @DisplayName("POST /api/sample/enum/user 传入未知枚举 code 触发 Jackson 反序列化异常拦截")
    void shouldFailWhenInvalidEnumCodePassedToJackson() throws Exception {
        String json = "{\"username\": \"钱七\", \"statusCode\": 1, \"status\": 999, \"orderType\": \"normal\"}";
        mockMvc.perform(post("/api/sample/enum/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)));
    }
}
