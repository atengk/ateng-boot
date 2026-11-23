package io.github.atengk.boot.sample.liteflow;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.property.LiteflowConfig;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import io.github.atengk.boot.liteflow.thread.LiteflowGlobalExecutorBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiteFlow 场景样例工程上下文加载与基础自动装配测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@DisplayName("LiteFlow 样例工程上下文加载测试")
class SampleLiteflowApplicationTest {

    @Autowired(required = false)
    private FlowExecutor flowExecutor;

    @Autowired(required = false)
    private LiteflowTemplate liteflowTemplate;

    @Autowired(required = false)
    private LiteflowConfig liteflowConfig;

    @Autowired(required = false)
    private AtengLiteflowProperties properties;

    @Test
    @DisplayName("验证 Spring 容器中 LiteFlow 核心组件加载与线程池接管配置")
    void contextLoads() {
        assertThat(flowExecutor).isNotNull();
        assertThat(liteflowTemplate).isNotNull();
        assertThat(liteflowConfig).isNotNull();
        assertThat(properties).isNotNull();
        assertThat(liteflowConfig.getGlobalThreadPoolExecutorClass())
                .isEqualTo(LiteflowGlobalExecutorBuilder.class.getName());
    }
}
