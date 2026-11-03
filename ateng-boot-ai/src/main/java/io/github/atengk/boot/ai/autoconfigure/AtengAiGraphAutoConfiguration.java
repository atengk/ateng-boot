package io.github.atengk.boot.ai.autoconfigure;

import io.github.atengk.boot.ai.checkpoint.GraphCheckpointSaver;
import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.checkpoint.RedisCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.GraphWorkflowRegistry;
import io.github.atengk.boot.ai.properties.AtengAiGraphProperties;
import io.github.atengk.boot.ai.properties.CheckpointStoreType;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 认知状态图底座自动装配类
 * <p>
 * 注册 LangGraph4j 检查点存储器（双模自适应），根据配置平滑切换单机内存与 Redis 分布式集群存储。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@ConditionalOnClass(StateGraph.class)
@ConditionalOnProperty(prefix = "ateng.boot.ai.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAiGraphProperties.class)
public class AtengAiGraphAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengAiGraphAutoConfiguration.class);

    /**
     * 自动装配认知图检查点持久化存储器
     *
     * @param properties 配置属性
     * @param redisTemplateProvider Redis 模板提供者
     * @return 检查点存储器实例
     */
    @Bean
    @ConditionalOnMissingBean({GraphCheckpointSaver.class, org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver.class})
    public GraphCheckpointSaver graphCheckpointSaver(
            AtengAiGraphProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {

        AtengAiGraphProperties.CheckpointProperties config = properties.getCheckpoint();
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();

        if (config.getType() == CheckpointStoreType.REDIS) {
            if (redisTemplate == null) {
                throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                        "已配置 ateng.boot.ai.graph.checkpoint.type=REDIS，但 Spring 容器中未找到 StringRedisTemplate Bean，请检查 Redis 依赖与连接配置");
            }
            log.info("AI 状态图检查点启用分布式 Redis 存储 [keyPrefix={}, ttl={}]", config.getKeyPrefix(), config.getCheckpointTtl());
            return new RedisCheckpointSaver(redisTemplate, config);
        }

        if (config.getType() == CheckpointStoreType.AUTO && redisTemplate != null) {
            log.info("AI 状态图检查点自动探测到 Redis，启用分布式 Redis 存储 [keyPrefix={}, ttl={}]", config.getKeyPrefix(), config.getCheckpointTtl());
            return new RedisCheckpointSaver(redisTemplate, config);
        }

        log.info("AI 状态图检查点启用单机内存存储 (MemoryCheckpointSaver)");
        return new MemoryCheckpointSaver();
    }

    /**
     * 自动装配认知图工作流全局注册表
     *
     * @param workflowsProvider 工作流 Bean 列表提供者
     * @return 注册表实例
     */
    @Bean
    @ConditionalOnMissingBean
    public GraphWorkflowRegistry graphWorkflowRegistry(ObjectProvider<List<GraphWorkflow<?>>> workflowsProvider) {
        return new GraphWorkflowRegistry(workflowsProvider);
    }
}
