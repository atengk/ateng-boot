package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.exception.MilvusResultCode;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * MilvusTemplate 基础能力与双核穿透单元测试 (TestDouble 内存桩模式)
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusTemplateBasicTest {

    private MilvusClientV2 mockClient;
    private AtengMilvusProperties properties;
    private MilvusTemplate milvusTemplate;

    @BeforeEach
    void setUp() {
        mockClient = Mockito.mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        milvusTemplate = new MilvusTemplate(mockClient, properties);
    }

    @Test
    @DisplayName("测试双核穿透与属性获取")
    void testGetClientAndProperties() {
        assertThat(milvusTemplate.getClient()).isSameAs(mockClient);
        assertThat(milvusTemplate.getProperties()).isSameAs(properties);
    }

    @Test
    @DisplayName("测试无损连通性探测 testConnection")
    void testConnection() {
        when(mockClient.clientIsReady()).thenReturn(true);
        assertThat(milvusTemplate.testConnection()).isTrue();

        when(mockClient.clientIsReady()).thenReturn(false);
        assertThat(milvusTemplate.testConnection()).isFalse();

        when(mockClient.clientIsReady()).thenThrow(new RuntimeException("gRPC connect error"));
        assertThat(milvusTemplate.testConnection()).isFalse();
    }

    @Test
    @DisplayName("测试服务端版本获取")
    void testGetServerVersion() {
        when(mockClient.getServerVersion()).thenReturn("v2.4.4");
        assertThat(milvusTemplate.getServerVersion()).isEqualTo("v2.4.4");
    }

    @Test
    @DisplayName("测试 execute 回调执行与异常自动转译")
    void testExecuteWithExceptionTranslation() {
        when(mockClient.getServerVersion()).thenThrow(new RuntimeException("collection not found: demo"));

        assertThatThrownBy(() -> milvusTemplate.getServerVersion())
                .isInstanceOf(MilvusException.class)
                .hasMessageContaining("集合不存在")
                .satisfies(ex -> {
                    MilvusException milvusEx = (MilvusException) ex;
                    assertThat(milvusEx.getResultCode()).isEqualTo(MilvusResultCode.COLLECTION_NOT_FOUND);
                    assertThat(milvusEx.getCause()).isNotNull();
                });
    }
}
