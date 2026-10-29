package io.github.atengk.boot.rocketmq.container;

import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.listener.RocketmqListenerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * RocketMQ 5.x 运行期消费监听容器动态流控与生命周期运维管控门面
 *
 * <p>核心职责：
 * 1. 端点审计与可视：纳管所有声明式 {@code @RocketmqListener} 端点，提供端点 ID 集合、端点元数据明细检索；
 * 2. 动态暂停与恢复削峰（pause / resume）：在维护窗口或大促流量激增时一键暂停端点，使新拉取消息立即返回 FAILURE 触发 Broker 阶梯退避重试，实现零线程/内存压力的极速削峰；
 * 3. 动态物理启停（stop / start）：支持在不重启应用的前提下，按端点热关闭底层物理 PushConsumer 或重新组装激活；
 * 4. 批量全局调度：提供全量端点 pauseAll / resumeAll / stopAll / startAll 运维能力。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class RocketmqContainerManager {

    private final RocketmqConsumerContainer container;

    public RocketmqContainerManager(RocketmqConsumerContainer container) {
        this.container = container;
    }

    // =========================================================================
    // 1. 端点检索与审计可见性 (Endpoint Lookup & Visibility)
    // =========================================================================

    /**
     * 获取所有已注册监听端点的唯一标识 ID 集合
     *
     * @return 不可变端点 ID 集合
     */
    public Set<String> getEndpointIds() {
        return container != null ? container.getEndpointIds() : Collections.emptySet();
    }

    /**
     * 获取所有已注册监听端点的元数据明细列表
     *
     * @return 不可变端点列表
     */
    public List<RocketmqListenerEndpoint> getEndpoints() {
        return container != null ? container.getEndpoints() : Collections.emptyList();
    }

    /**
     * 根据端点 ID 获取特定端点元数据
     *
     * @param id 监听端点 ID（如注解中指定的 id 或默认的 consumerGroup#topic）
     * @return 匹配的端点元数据，未找到返回 null
     */
    public RocketmqListenerEndpoint getEndpoint(String id) {
        if (!StringUtils.hasText(id) || container == null) {
            return null;
        }
        return container.getEndpoint(id);
    }

    /**
     * 智能匹配端点列表（优先按 endpointId 精确匹配，未命中则按 topic 匹配）
     *
     * @param idOrTopic 端点 ID 或订阅主题名称
     * @return 匹配的不可变端点列表
     */
    public List<RocketmqListenerEndpoint> findEndpoints(String idOrTopic) {
        if (!StringUtils.hasText(idOrTopic) || container == null) {
            return Collections.emptyList();
        }

        String target = idOrTopic.trim();
        // 1. 优先精确匹配 ID
        RocketmqListenerEndpoint direct = container.getEndpoint(target);
        if (direct != null) {
            return List.of(direct);
        }

        // 2. 次选按 Topic 匹配
        List<RocketmqListenerEndpoint> matched = new ArrayList<>();
        for (RocketmqListenerEndpoint ep : container.getEndpoints()) {
            if (target.equals(ep.getTopic())) {
                matched.add(ep);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    // =========================================================================
    // 2. 动态暂停与恢复削峰 (Pause & Resume Flow Control)
    // =========================================================================

    /**
     * 暂停指定端点或指定主题下的所有监听端点消费
     *
     * <p>暂停后 PushConsumer 保持底层心跳连接，但内部 Invoker 收到消息后直接返回 ConsumeResult.FAILURE，
     * 促使 RocketMQ Broker 进行阶梯退避延时重试，杜绝下游压垮与本地线程池耗尽。</p>
     *
     * @param idOrTopic 端点 ID 或主题名称
     * @return 若找到匹配端点并成功下发暂停返回 true，未匹配到任何端点返回 false
     */
    public boolean pause(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<RocketmqListenerEndpoint> endpoints = findEndpoints(idOrTopic);
        if (endpoints.isEmpty()) {
            log.warn("[MQ-CONTAINER-PAUSE] 未找到匹配的消费监听端点: idOrTopic={}", idOrTopic);
            return false;
        }

        int pausedCount = 0;
        for (RocketmqListenerEndpoint ep : endpoints) {
            if (ep.getInvoker() != null && !ep.getInvoker().isPaused()) {
                ep.getInvoker().pause();
                pausedCount++;
            }
        }
        log.info("[MQ-CONTAINER-PAUSE] 成功暂停消费监听端点: idOrTopic={}, matchedCount={}, pausedCount={}",
                idOrTopic, endpoints.size(), pausedCount);
        return true;
    }

    /**
     * 恢复指定端点或指定主题下的所有监听端点消费
     *
     * @param idOrTopic 端点 ID 或主题名称
     * @return 若找到匹配端点并成功下发恢复返回 true，未匹配到任何端点返回 false
     */
    public boolean resume(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<RocketmqListenerEndpoint> endpoints = findEndpoints(idOrTopic);
        if (endpoints.isEmpty()) {
            log.warn("[MQ-CONTAINER-RESUME] 未找到匹配的消费监听端点: idOrTopic={}", idOrTopic);
            return false;
        }

        int resumedCount = 0;
        for (RocketmqListenerEndpoint ep : endpoints) {
            if (ep.getInvoker() != null && ep.getInvoker().isPaused()) {
                ep.getInvoker().resume();
                resumedCount++;
            }
        }
        log.info("[MQ-CONTAINER-RESUME] 成功恢复消费监听端点: idOrTopic={}, matchedCount={}, resumedCount={}",
                idOrTopic, endpoints.size(), resumedCount);
        return true;
    }

    /**
     * 全局暂停所有已注册监听端点的消费
     *
     * @return 本次成功暂停的端点数量
     */
    public int pauseAll() {
        if (container == null) {
            return 0;
        }
        int count = 0;
        for (RocketmqListenerEndpoint ep : container.getEndpoints()) {
            if (ep.getInvoker() != null && !ep.getInvoker().isPaused()) {
                ep.getInvoker().pause();
                count++;
            }
        }
        log.info("[MQ-CONTAINER-PAUSE-ALL] 成功暂停所有消费监听端点: totalPaused={}", count);
        return count;
    }

    /**
     * 全局恢复所有已暂停监听端点的消费
     *
     * @return 本次成功恢复的端点数量
     */
    public int resumeAll() {
        if (container == null) {
            return 0;
        }
        int count = 0;
        for (RocketmqListenerEndpoint ep : container.getEndpoints()) {
            if (ep.getInvoker() != null && ep.getInvoker().isPaused()) {
                ep.getInvoker().resume();
                count++;
            }
        }
        log.info("[MQ-CONTAINER-RESUME-ALL] 成功恢复所有消费监听端点: totalResumed={}", count);
        return count;
    }

    /**
     * 查询指定端点或主题是否处于暂停状态
     *
     * @param idOrTopic 端点 ID 或主题名称
     * @return 若匹配到的所有端点均处于暂停状态且数量大于 0 返回 true；未匹配或存在运行端点返回 false
     */
    public boolean isPaused(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<RocketmqListenerEndpoint> endpoints = findEndpoints(idOrTopic);
        if (endpoints.isEmpty()) {
            return false;
        }
        return endpoints.stream().allMatch(RocketmqListenerEndpoint::isPaused);
    }

    // =========================================================================
    // 3. 动态物理启停管控 (Start & Stop Lifecycle Control)
    // =========================================================================

    /**
     * 启动指定端点或指定主题下的所有监听端点底层 PushConsumer
     *
     * @param idOrTopic 端点 ID 或主题名称
     * @return 若找到匹配端点并处理返回 true，未匹配到任何端点返回 false
     */
    public boolean start(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<RocketmqListenerEndpoint> endpoints = findEndpoints(idOrTopic);
        if (endpoints.isEmpty()) {
            log.warn("[MQ-CONTAINER-START] 未找到匹配的消费监听端点: idOrTopic={}", idOrTopic);
            return false;
        }

        int startedCount = 0;
        for (RocketmqListenerEndpoint ep : endpoints) {
            if (!ep.isRunning()) {
                if (container.startEndpoint(ep)) {
                    startedCount++;
                }
            }
        }
        log.info("[MQ-CONTAINER-START] 成功启动消费监听端点: idOrTopic={}, matchedCount={}, startedCount={}",
                idOrTopic, endpoints.size(), startedCount);
        return true;
    }

    /**
     * 优雅停止指定端点或指定主题下的所有监听端点底层 PushConsumer
     *
     * @param idOrTopic 端点 ID 或主题名称
     * @return 若找到匹配端点并处理返回 true，未匹配到任何端点返回 false
     */
    public boolean stop(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<RocketmqListenerEndpoint> endpoints = findEndpoints(idOrTopic);
        if (endpoints.isEmpty()) {
            log.warn("[MQ-CONTAINER-STOP] 未找到匹配的消费监听端点: idOrTopic={}", idOrTopic);
            return false;
        }

        int stoppedCount = 0;
        for (RocketmqListenerEndpoint ep : endpoints) {
            if (ep.isRunning()) {
                if (container.stopEndpoint(ep)) {
                    stoppedCount++;
                }
            }
        }
        log.info("[MQ-CONTAINER-STOP] 成功停止消费监听端点: idOrTopic={}, matchedCount={}, stoppedCount={}",
                idOrTopic, endpoints.size(), stoppedCount);
        return true;
    }

    /**
     * 启动所有处于停止状态的消费监听端点
     *
     * @return 本次成功启动的端点数量
     */
    public int startAll() {
        if (container == null) {
            return 0;
        }
        int count = 0;
        for (RocketmqListenerEndpoint ep : container.getEndpoints()) {
            if (!ep.isRunning()) {
                if (container.startEndpoint(ep)) {
                    count++;
                }
            }
        }
        log.info("[MQ-CONTAINER-START-ALL] 成功启动所有已停止消费监听端点: totalStarted={}", count);
        return count;
    }

    /**
     * 优雅停止所有处于运行状态的消费监听端点
     *
     * @return 本次成功停止的端点数量
     */
    public int stopAll() {
        if (container == null) {
            return 0;
        }
        int count = 0;
        for (RocketmqListenerEndpoint ep : container.getEndpoints()) {
            if (ep.isRunning()) {
                if (container.stopEndpoint(ep)) {
                    count++;
                }
            }
        }
        log.info("[MQ-CONTAINER-STOP-ALL] 成功停止所有运行中消费监听端点: totalStopped={}", count);
        return count;
    }

    /**
     * 查询指定端点或主题是否处于运行中状态
     *
     * @param idOrTopic 端点 ID 或主题名称
     * @return 若匹配到的所有端点均处于运行状态且数量大于 0 返回 true；未匹配或存在已停止端点返回 false
     */
    public boolean isRunning(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<RocketmqListenerEndpoint> endpoints = findEndpoints(idOrTopic);
        if (endpoints.isEmpty()) {
            return false;
        }
        return endpoints.stream().allMatch(RocketmqListenerEndpoint::isRunning);
    }
}
