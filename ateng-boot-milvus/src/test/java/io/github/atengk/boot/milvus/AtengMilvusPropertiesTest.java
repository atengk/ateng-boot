package io.github.atengk.boot.milvus;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.milvus.autoconfigure.AtengMilvusAutoConfiguration;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milvus 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AtengMilvusPropertiesTest {

    @Test
    @DisplayName("测试配置属性默认值")
    void testDefaultValues() {
        AtengMilvusProperties properties = new AtengMilvusProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(19530);
        assertThat(properties.getDatabase()).isEqualTo("default");
        assertThat(properties.isSecure()).isFalse();
        assertThat(properties.getConsistencyLevel()).isEqualTo(MilvusConsistencyLevel.BOUNDED);
        assertThat(properties.isAutoCreatePartition()).isFalse();
        assertThat(properties.getTenantFieldName()).isEqualTo("tenant_id");
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getKeepAliveTime()).isEqualTo(Duration.ofSeconds(55));
        assertThat(properties.getKeepAliveTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.getIdleTimeout()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getRpcDeadline()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getBatchSize()).isEqualTo(1000);
        assertThat(properties.getMaxRetries()).isEqualTo(3);
        assertThat(properties.getRetryDelay()).isEqualTo(Duration.ofMillis(200));

        // 默认解析出的 URI
        assertThat(properties.resolveEffectiveUri()).isEqualTo("http://127.0.0.1:19530");
    }

    @Test
    @DisplayName("测试 MilvusConsistencyLevel 实现 IBaseEnum<String> 契约及 EnumUtil 互操作")
    void testConsistencyLevelBaseEnumContract() {
        assertThat(IBaseEnum.class).isAssignableFrom(MilvusConsistencyLevel.class);

        for (MilvusConsistencyLevel level : MilvusConsistencyLevel.values()) {
            assertThat(level.getCode()).isEqualTo(level.name());
            assertThat(level.getLabel()).isNotBlank();
            assertThat(level.getNativeLevel()).isNotNull();
            assertThat(level.matches(level.name())).isTrue();
        }

        assertThat(MilvusConsistencyLevel.STRONG.getNativeLevel()).isEqualTo(ConsistencyLevel.STRONG);
        assertThat(MilvusConsistencyLevel.BOUNDED.getNativeLevel()).isEqualTo(ConsistencyLevel.BOUNDED);
        assertThat(MilvusConsistencyLevel.EVENTUALLY.getNativeLevel()).isEqualTo(ConsistencyLevel.EVENTUALLY);

        assertThat(EnumUtil.fromCode(MilvusConsistencyLevel.class, "STRONG"))
                .contains(MilvusConsistencyLevel.STRONG);
        assertThat(EnumUtil.fromCode(MilvusConsistencyLevel.class, "BOUNDED"))
                .contains(MilvusConsistencyLevel.BOUNDED);
        assertThat(EnumUtil.fromCode(MilvusConsistencyLevel.class, "EVENTUALLY"))
                .contains(MilvusConsistencyLevel.EVENTUALLY);
        assertThat(EnumUtil.isValidCode(MilvusConsistencyLevel.class, "STRONG")).isTrue();
        assertThat(EnumUtil.isValidCode(MilvusConsistencyLevel.class, "UNKNOWN")).isFalse();

        OptionItem<String> option = MilvusConsistencyLevel.EVENTUALLY.toOption();
        assertThat(option.getCode()).isEqualTo("EVENTUALLY");
        assertThat(option.getLabel()).isNotBlank();

        List<OptionItem<String>> options = EnumUtil.toOptions(MilvusConsistencyLevel.class);
        assertThat(options).hasSize(3);

        // Jackson 序列化与反序列化契约验证（含非法枚举编码快速失败）
        assertThat(JsonUtil.toJsonString(MilvusConsistencyLevel.STRONG)).isEqualTo("\"STRONG\"");
        assertThat(JsonUtil.parseObject("\"bounded\"", MilvusConsistencyLevel.class))
                .isEqualTo(MilvusConsistencyLevel.BOUNDED);
        assertThat(MilvusConsistencyLevel.fromValue(" ")).isNull();
        assertThatThrownBy(() -> JsonUtil.parseObject("\"INVALID_LEVEL\"", MilvusConsistencyLevel.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("测试 Spring Boot 配置属性绑定与向后兼容枚举字符串解析")
    void testPropertiesBindingAndBackwardCompatibility() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengMilvusAutoConfiguration.class))
                .withBean(MilvusClientV2.class, () -> Mockito.mock(MilvusClientV2.class))
                .withPropertyValues(
                        "ateng.boot.milvus.consistency-level=STRONG",
                        "ateng.boot.milvus.batch-size=500",
                        "ateng.boot.milvus.max-retries=5",
                        "ateng.boot.milvus.retry-delay=350ms"
                )
                .run(context -> {
                    AtengMilvusProperties bound = context.getBean(AtengMilvusProperties.class);
                    assertThat(bound.getConsistencyLevel()).isEqualTo(MilvusConsistencyLevel.STRONG);
                    assertThat(bound.getBatchSize()).isEqualTo(500);
                    assertThat(bound.getMaxRetries()).isEqualTo(5);
                    assertThat(bound.getRetryDelay()).isEqualTo(Duration.ofMillis(350));
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengMilvusAutoConfiguration.class))
                .withBean(MilvusClientV2.class, () -> Mockito.mock(MilvusClientV2.class))
                .withPropertyValues("ateng.boot.milvus.consistency-level=eventually")
                .run(context -> {
                    AtengMilvusProperties bound = context.getBean(AtengMilvusProperties.class);
                    assertThat(bound.getConsistencyLevel()).isEqualTo(MilvusConsistencyLevel.EVENTUALLY);
                });
    }

    @Test
    @DisplayName("测试显式配置 URI 与 TLS 协议推导")
    void testResolveEffectiveUri() {
        AtengMilvusProperties properties = new AtengMilvusProperties();

        // 显式配置完整 URI
        properties.setUri("https://milvus-standalone.internal:19530");
        assertThat(properties.resolveEffectiveUri()).isEqualTo("https://milvus-standalone.internal:19530");

        // 移除显式 URI，基于 host/port/secure 动态推导
        properties.setUri(null);
        properties.setHost("192.168.1.100");
        properties.setPort(19531);
        properties.setSecure(true);
        assertThat(properties.resolveEffectiveUri()).isEqualTo("https://192.168.1.100:19531");
    }
}
