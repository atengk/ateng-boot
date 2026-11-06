package io.github.atengk.boot.ai.openapi;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 企业 OpenAPI 契约转 Agent 工具适配器 OpenApiToolAdapter 单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class OpenApiToolAdapterTest {

    private static final String OPENAPI_V3_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {
                "title": "Order Management API",
                "version": "1.0.0"
              },
              "servers": [
                {
                  "url": "https://order-service.internal/api/v1"
                }
              ],
              "paths": {
                "/orders": {
                  "get": {
                    "operationId": "listOrders",
                    "summary": "分页查询订单列表",
                    "parameters": [
                      {
                        "name": "page",
                        "in": "query",
                        "required": false,
                        "description": "页码",
                        "schema": {
                          "type": "integer"
                        }
                      },
                      {
                        "name": "status",
                        "in": "query",
                        "required": false,
                        "description": "订单状态",
                        "schema": {
                          "type": "string"
                        }
                      }
                    ]
                  },
                  "post": {
                    "operationId": "createOrder",
                    "summary": "创建新订单",
                    "requestBody": {
                      "required": true,
                      "content": {
                        "application/json": {
                          "schema": {
                            "$ref": "#/components/schemas/CreateOrderRequest"
                          }
                        }
                      }
                    }
                  }
                },
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrderById",
                    "summary": "根据唯一标识查询订单详情",
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "description": "订单主键ID",
                        "schema": {
                          "type": "integer"
                        }
                      },
                      {
                        "name": "includeDetails",
                        "in": "query",
                        "required": false,
                        "description": "是否包含商品行项目",
                        "schema": {
                          "type": "boolean"
                        }
                      }
                    ]
                  },
                  "put": {
                    "operationId": "updateOrder",
                    "summary": "更新订单信息",
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "description": "订单ID",
                        "schema": {
                          "type": "integer"
                        }
                      }
                    ],
                    "requestBody": {
                      "required": true,
                      "content": {
                        "application/json": {
                          "schema": {
                            "$ref": "#/components/schemas/UpdateOrderRequest"
                          }
                        }
                      }
                    }
                  },
                  "delete": {
                    "operationId": "deleteOrder",
                    "summary": "根据ID删除或作废订单",
                    "parameters": [
                      {
                        "name": "id",
                        "in": "path",
                        "required": true,
                        "description": "订单ID",
                        "schema": {
                          "type": "integer"
                        }
                      }
                    ]
                  }
                }
              },
              "components": {
                "schemas": {
                  "CreateOrderRequest": {
                    "type": "object",
                    "required": ["buyerName", "amount"],
                    "properties": {
                      "buyerName": {
                        "type": "string",
                        "description": "买家姓名"
                      },
                      "amount": {
                        "type": "number",
                        "description": "订单金额"
                      },
                      "channel": {
                        "type": "string",
                        "description": "销售渠道",
                        "enum": ["ONLINE", "OFFLINE", "APP"]
                      }
                    }
                  },
                  "UpdateOrderRequest": {
                    "type": "object",
                    "properties": {
                      "buyerName": {
                        "type": "string",
                        "description": "修改后的买家姓名"
                      },
                      "remark": {
                        "type": "string",
                        "description": "订单备注"
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final String SWAGGER_V2_SPEC = """
            {
              "swagger": "2.0",
              "info": {
                "title": "Swagger 2.0 User API",
                "version": "2.0.0"
              },
              "host": "user-service.internal",
              "basePath": "/v2",
              "schemes": ["http"],
              "paths": {
                "/users/{userId}": {
                  "get": {
                    "operationId": "getUser",
                    "summary": "查询用户信息",
                    "parameters": [
                      {
                        "name": "userId",
                        "in": "path",
                        "required": true,
                        "type": "string",
                        "description": "用户主键ID"
                      }
                    ]
                  },
                  "post": {
                    "operationId": "createUser",
                    "summary": "创建用户",
                    "parameters": [
                      {
                        "name": "body",
                        "in": "body",
                        "required": true,
                        "schema": {
                          "$ref": "#/definitions/UserDto"
                        }
                      }
                    ]
                  }
                }
              },
              "definitions": {
                "UserDto": {
                  "type": "object",
                  "required": ["username"],
                  "properties": {
                    "username": {
                      "type": "string",
                      "description": "用户名"
                    },
                    "age": {
                      "type": "integer",
                      "description": "年龄"
                    }
                  }
                }
              }
            }
            """;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("测试 OpenAPI 3.0 规范解析并转换为 ToolSpecification 映射")
    void testOpenApiV3ParsingAndSpecifications() {
        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, new OpenApiToolProperties());

        assertThat(adapter.getResolvedBaseUrl()).isEqualTo("https://order-service.internal/api/v1");
        Map<ToolSpecification, ToolExecutor> tools = adapter.getTools();
        assertThat(tools).hasSize(5);

        List<ToolSpecification> specs = adapter.getToolSpecifications();
        List<String> opNames = specs.stream().map(ToolSpecification::name).toList();
        assertThat(opNames).containsExactlyInAnyOrder("listOrders", "createOrder", "getOrderById", "updateOrder", "deleteOrder");

        // 验证 getOrderById 参数
        ToolSpecification getOrderSpec = specs.stream()
                .filter(s -> "getOrderById".equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(getOrderSpec.description()).isEqualTo("根据唯一标识查询订单详情");
        assertThat(getOrderSpec.parameters()).isNotNull();
        assertThat(getOrderSpec.parameters().properties()).containsKey("id");
        assertThat(getOrderSpec.parameters().properties()).containsKey("includeDetails");
        assertThat(getOrderSpec.parameters().required()).contains("id");

        // 验证 createOrder 模式展平与 $ref 转换
        ToolSpecification createOrderSpec = specs.stream()
                .filter(s -> "createOrder".equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(createOrderSpec.parameters().properties()).containsKey("buyerName");
        assertThat(createOrderSpec.parameters().properties()).containsKey("amount");
        assertThat(createOrderSpec.parameters().properties()).containsKey("channel");
        assertThat(createOrderSpec.parameters().properties()).containsKey("body");
        assertThat(createOrderSpec.parameters().required()).contains("buyerName", "amount");
    }

    @Test
    @DisplayName("测试 Swagger 2.0 规范解析与 definitions 模式转换")
    void testSwaggerV2Parsing() {
        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(SWAGGER_V2_SPEC, new OpenApiToolProperties());

        assertThat(adapter.getResolvedBaseUrl()).isEqualTo("http://user-service.internal/v2");
        Map<ToolSpecification, ToolExecutor> tools = adapter.getTools();
        assertThat(tools).hasSize(2);

        ToolSpecification createUserSpec = adapter.getToolSpecifications().stream()
                .filter(s -> "createUser".equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(createUserSpec.parameters().properties()).containsKey("username");
        assertThat(createUserSpec.parameters().properties()).containsKey("age");
        assertThat(createUserSpec.parameters().properties()).containsKey("body");
        assertThat(createUserSpec.parameters().required()).contains("username");
    }

    @Test
    @DisplayName("测试白名单过滤 includeOperations")
    void testIncludeOperationsFilter() {
        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .includeOperations(Set.of("getOrderById", "createOrder"))
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props);
        assertThat(adapter.getTools()).hasSize(2);
        assertThat(adapter.getToolExecutor("getOrderById")).isNotNull();
        assertThat(adapter.getToolExecutor("createOrder")).isNotNull();
        assertThat(adapter.getToolExecutor("deleteOrder")).isNull();
    }

    @Test
    @DisplayName("测试黑名单过滤 excludeOperations")
    void testExcludeOperationsFilter() {
        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .excludeOperations(Set.of("deleteOrder", "updateOrder"))
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props);
        assertThat(adapter.getTools()).hasSize(3);
        assertThat(adapter.getToolExecutor("deleteOrder")).isNull();
        assertThat(adapter.getToolExecutor("updateOrder")).isNull();
        assertThat(adapter.getToolExecutor("listOrders")).isNotNull();
    }

    @Test
    @DisplayName("测试只读模式 readOnly 屏蔽写操作")
    void testReadOnlyMode() {
        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .readOnly(true)
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props);
        Map<ToolSpecification, ToolExecutor> tools = adapter.getTools();
        // 仅保留 listOrders 与 getOrderById
        assertThat(tools).hasSize(2);
        List<String> names = adapter.getToolSpecifications().stream().map(ToolSpecification::name).toList();
        assertThat(names).containsExactlyInAnyOrder("listOrders", "getOrderById");
    }

    @Test
    @DisplayName("测试 HTTP 调度执行：路径变量替换、查询参数拼接与 4D 上下文透传")
    void testHttpExecutionWithContextPropagation() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://order-service.internal/api/v1");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .baseUrl("https://order-service.internal/api/v1")
                .propagateContext(true)
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props, restClient);

        // 设置 4D 治理上下文
        MDC.put("traceId", "test-trace-1001");
        UserContextHolder.setUserId("user-super-admin");
        TenantContextHolder.setTenantId("tenant-enterprise-01");

        mockServer.expect(requestTo("https://order-service.internal/api/v1/orders/12345?includeDetails=true"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Trace-Id", "test-trace-1001"))
                .andExpect(header("X-User-Id", "user-super-admin"))
                .andExpect(header("X-Tenant-Id", "tenant-enterprise-01"))
                .andRespond(withSuccess("{\"id\":12345,\"status\":\"PAID\",\"buyerName\":\"ZhangSan\"}", MediaType.APPLICATION_JSON));

        // 直接通过 execute 调用
        String resultJson = adapter.execute("getOrderById", Map.of("id", 12345, "includeDetails", true));

        assertThat(resultJson).contains("12345");
        assertThat(resultJson).contains("ZhangSan");
        mockServer.verify();
    }

    @Test
    @DisplayName("测试动态 HTTP 上下文透传：自动透传 RequestContextHolder 中的 Authorization 凭据")
    void testDynamicAuthorizationPropagation() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://order-service.internal/api/v1");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .baseUrl("https://order-service.internal/api/v1")
                .propagateContext(true)
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props, restClient);

        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new DummyRequestAttributes());

        try {
            mockServer.expect(requestTo("https://order-service.internal/api/v1/orders/8888"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header("Authorization", "Bearer dynamic-user-jwt-token-999"))
                    .andRespond(withSuccess("{\"id\":8888,\"status\":\"SHIPPED\"}", MediaType.APPLICATION_JSON));

            String result = adapter.execute("getOrderById", Map.of("id", 8888));
            assertThat(result).contains("SHIPPED");
            mockServer.verify();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    static class DummyRequestAttributes implements org.springframework.web.context.request.RequestAttributes {
        public Object getRequest() {
            return new Object() {
                public String getHeader(String name) {
                    return "Authorization".equalsIgnoreCase(name) ? "Bearer dynamic-user-jwt-token-999" : null;
                }
            };
        }

        @Override public Object getAttribute(String name, int scope) { return null; }
        @Override public void setAttribute(String name, Object value, int scope) {}
        @Override public void removeAttribute(String name, int scope) {}
        @Override public String[] getAttributeNames(int scope) { return new String[0]; }
        @Override public void registerDestructionCallback(String name, Runnable callback, int scope) {}
        @Override public Object resolveReference(String key) { return null; }
        @Override public String getSessionId() { return "dummy-session"; }
        @Override public Object getSessionMutex() { return this; }
    }

    @Test
    @DisplayName("测试 POST 请求体扁平化序列化与 LangChain4j ToolExecutor 执行")
    void testPostExecutionWithToolExecutor() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://order-service.internal/api/v1");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .baseUrl("https://order-service.internal/api/v1")
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props, restClient);

        mockServer.expect(requestTo("https://order-service.internal/api/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(content().json("{\"buyerName\":\"LiSi\",\"amount\":199.9,\"channel\":\"ONLINE\"}"))
                .andRespond(withSuccess("{\"id\":99901,\"buyerName\":\"LiSi\",\"amount\":199.9}", MediaType.APPLICATION_JSON));

        ToolExecutor executor = adapter.getToolExecutor("createOrder");
        assertThat(executor).isNotNull();

        // 模拟 LangChain4j ToolExecutionRequest
        String argsJson = JsonUtil.toJsonString(Map.of("buyerName", "LiSi", "amount", 199.9, "channel", "ONLINE"));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("createOrder")
                .arguments(argsJson)
                .build();

        String response = executor.execute(request, null);
        assertThat(response).contains("99901");
        assertThat(response).contains("LiSi");
        mockServer.verify();
    }

    @Test
    @DisplayName("测试远程 HTTP 404/500 异常时优雅转译为语义化自愈错误反馈")
    void testHttpErrorGracefulHandling() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://order-service.internal/api/v1");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenApiToolProperties props = OpenApiToolProperties.builder()
                .baseUrl("https://order-service.internal/api/v1")
                .build();

        OpenApiToolAdapter adapter = OpenApiToolAdapter.fromString(OPENAPI_V3_SPEC, props, restClient);

        mockServer.expect(requestTo("https://order-service.internal/api/v1/orders/99999"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound().body("{\"message\":\"Order not found\"}"));

        String result = adapter.execute("getOrderById", Map.of("id", 99999));
        assertThat(result).contains("\"error\":true");
        assertThat(result).contains("\"statusCode\":404");
        assertThat(result).contains("Order not found");
        mockServer.verify();
    }
}
