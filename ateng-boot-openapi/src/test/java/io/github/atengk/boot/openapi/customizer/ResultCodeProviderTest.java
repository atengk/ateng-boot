package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.github.atengk.boot.openapi.spi.ResultCodeProvider;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务状态码字典 SPI (ResultCodeProvider) 与字典定制器测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("业务状态码字典 SPI (ResultCodeProvider) 单元测试")
class ResultCodeProviderTest {

    enum OrderResultCode implements IResultCode {
        ORDER_NOT_FOUND(6001, "订单不存在"),
        ORDER_EXPIRED(6002, "订单已过期超时"),
        ORDER_PAID_ALREADY(6003, "订单已被支付");

        private final Integer code;
        private final String msg;

        OrderResultCode(Integer code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getMsg() {
            return msg;
        }
    }

    enum PayResultCode implements IResultCode {
        PAY_FAILED(7001, "支付网关扣款失败"),
        PAY_INSUFFICIENT_BALANCE(7002, "账户余额不足");

        private final Integer code;
        private final String msg;

        PayResultCode(Integer code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getMsg() {
            return msg;
        }
    }

    @Test
    @DisplayName("测试单个 ResultCodeProvider 成功向 Info.description 追加自定义状态码表格")
    void testSingleResultCodeProviderAppendsTable() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setShowResultCodes(true);

        ResultCodeProvider orderProvider = new ResultCodeProvider() {
            @Override
            public String getTitle() {
                return "### 订单中心状态码字典 (Order Result Codes)";
            }

            @Override
            public Collection<? extends IResultCode> getResultCodes() {
                return Arrays.asList(OrderResultCode.values());
            }

            @Override
            public String resolveScenario(IResultCode resultCode) {
                if (resultCode == OrderResultCode.ORDER_NOT_FOUND) {
                    return "查询或变更订单时数据库无记录";
                }
                return resultCode.getMsg();
            }
        };

        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties, List.of(orderProvider));
        OpenAPI openApi = new OpenAPI().info(new Info().description("Base Description"));

        customizer.customise(openApi);

        String description = openApi.getInfo().getDescription();
        assertThat(description).isNotNull();
        // 包含基础描述
        assertThat(description).contains("Base Description");
        // 包含系统通用状态码字典
        assertThat(description).contains(ResultCodeDictionaryCustomizer.DICTIONARY_TITLE);
        assertThat(description).contains("| `200` | 操作成功 | 请求正常处理完成 |");
        // 包含订单业务状态码字典
        assertThat(description).contains("### 订单中心状态码字典 (Order Result Codes)");
        assertThat(description).contains("| `6001` | 订单不存在 | 查询或变更订单时数据库无记录 |");
        assertThat(description).contains("| `6002` | 订单已过期超时 | 订单已过期超时 |");
        assertThat(description).contains("| `6003` | 订单已被支付 | 订单已被支付 |");
    }

    @Test
    @DisplayName("测试多个 ResultCodeProvider 严格遵循 getOrder 升序排列追加")
    void testMultipleResultCodeProvidersOrdered() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setShowResultCodes(true);

        ResultCodeProvider payProvider = new ResultCodeProvider() {
            @Override
            public String getTitle() {
                return "### 支付中心状态码字典";
            }

            @Override
            public Collection<? extends IResultCode> getResultCodes() {
                return Arrays.asList(PayResultCode.values());
            }

            @Override
            public int getOrder() {
                return 20; // 较靠后
            }
        };

        ResultCodeProvider orderProvider = new ResultCodeProvider() {
            @Override
            public String getTitle() {
                return "### 订单中心状态码字典";
            }

            @Override
            public Collection<? extends IResultCode> getResultCodes() {
                return Arrays.asList(OrderResultCode.values());
            }

            @Override
            public int getOrder() {
                return 10; // 较靠前
            }
        };

        // 故意逆序传入
        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties, List.of(payProvider, orderProvider));
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        String description = openApi.getInfo().getDescription();
        assertThat(description).isNotNull();

        int commonIndex = description.indexOf(ResultCodeDictionaryCustomizer.DICTIONARY_TITLE);
        int orderIndex = description.indexOf("### 订单中心状态码字典");
        int payIndex = description.indexOf("### 支付中心状态码字典");

        assertThat(commonIndex).isLessThan(orderIndex);
        assertThat(orderIndex).isLessThan(payIndex);
    }

    @Test
    @DisplayName("测试 ResultCodeProvider 返回默认标题、空集合或 null 元素的鲁棒性防卫")
    void testResultCodeProviderRobustness() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setShowResultCodes(true);

        ResultCodeProvider emptyProvider = new ResultCodeProvider() {
            @Override
            public Collection<? extends IResultCode> getResultCodes() {
                return Collections.emptyList();
            }
        };

        ResultCodeProvider nullListProvider = new ResultCodeProvider() {
            @Override
            public Collection<? extends IResultCode> getResultCodes() {
                return null;
            }
        };

        ResultCodeProvider defaultTitleProvider = new ResultCodeProvider() {
            @Override
            public Collection<? extends IResultCode> getResultCodes() {
                return Collections.singletonList(OrderResultCode.ORDER_NOT_FOUND);
            }
        };

        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(
                properties,
                Arrays.asList(emptyProvider, nullListProvider, defaultTitleProvider)
        );
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        String description = openApi.getInfo().getDescription();
        assertThat(description).isNotNull();
        // 包含默认标题
        assertThat(description).contains(ResultCodeDictionaryCustomizer.DEFAULT_CUSTOM_TITLE);
        assertThat(description).contains("| `6001` | 订单不存在 | 订单不存在 |");
    }

    @Test
    @DisplayName("测试防重幂等：重复执行 customise 不会追加重复字典")
    void testIdempotentCustomise() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setShowResultCodes(true);

        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);
        String firstPass = openApi.getInfo().getDescription();

        customizer.customise(openApi);
        String secondPass = openApi.getInfo().getDescription();

        assertThat(firstPass).isEqualTo(secondPass);
    }

    @Test
    @DisplayName("测试当 showResultCodes 为 false 时完全不追加任何字典")
    void testShowResultCodesFalse() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setShowResultCodes(false);

        ResultCodeDictionaryCustomizer customizer = new ResultCodeDictionaryCustomizer(properties);
        OpenAPI openApi = new OpenAPI().info(new Info().description("Original Desc"));

        customizer.customise(openApi);

        assertThat(openApi.getInfo().getDescription()).isEqualTo("Original Desc");
    }
}
