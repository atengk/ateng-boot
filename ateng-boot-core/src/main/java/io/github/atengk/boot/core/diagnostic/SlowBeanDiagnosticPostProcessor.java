package io.github.atengk.boot.core.diagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 慢 Spring Bean 初始化耗时诊断后处理器
 * 统计容器就绪期间所有 Bean 的初始化耗时，对于超过配置门限的慢 Bean 输出警告并收录清单供启动就绪卡片读取
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class SlowBeanDiagnosticPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(SlowBeanDiagnosticPostProcessor.class);

    private static final List<SlowBeanInfo> SLOW_BEANS = new CopyOnWriteArrayList<>();

    private final Map<String, Long> beanStartTimes = new ConcurrentHashMap<>();
    private final long thresholdMillis;

    public SlowBeanDiagnosticPostProcessor() {
        this(Duration.ofMillis(500));
    }

    public SlowBeanDiagnosticPostProcessor(Duration threshold) {
        this.thresholdMillis = (threshold != null && !threshold.isNegative() && !threshold.isZero())
                ? threshold.toMillis()
                : 0L;
    }

    /**
     * 获取当前 JVM 容器记录的所有慢 Bean 初始化清单快照
     *
     * @return 慢 Bean 信息不可变列表
     */
    public static List<SlowBeanInfo> getSlowBeans() {
        return Collections.unmodifiableList(SLOW_BEANS);
    }

    /**
     * 清理记录的慢 Bean 清单（主要用于测试隔离）
     */
    public static void clearSlowBeans() {
        SLOW_BEANS.clear();
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (thresholdMillis > 0 && beanName != null) {
            beanStartTimes.put(beanName, System.currentTimeMillis());
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (thresholdMillis > 0 && beanName != null) {
            Long startTime = beanStartTimes.remove(beanName);
            if (startTime != null) {
                long cost = System.currentTimeMillis() - startTime;
                if (cost >= thresholdMillis) {
                    String className = bean != null ? bean.getClass().getName() : "unknown";
                    SlowBeanInfo info = new SlowBeanInfo(beanName, className, cost);
                    SLOW_BEANS.add(info);
                    log.warn("[SLOW-BEAN] Spring Bean [{}] 初始化耗时 {} ms (阈值: {} ms)",
                            beanName, cost, thresholdMillis);
                }
            }
        }
        return bean;
    }

    /**
     * 慢 Bean 诊断快照载荷
     *
     * @param beanName      Bean 名称
     * @param beanClassName Bean 实现类完整类名
     * @param costMillis    初始化耗时（毫秒）
     */
    public record SlowBeanInfo(String beanName, String beanClassName, long costMillis) {
    }
}
