package io.github.atengk.boot.observability.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ateng-boot 受管线程池全量自动发现与度量指标绑定器
 * 自动扫描 Spring 容器内所有 ThreadPoolTaskExecutor、ThreadPoolExecutor 及虚拟线程执行器，
 * 按 Bean 标识打标并向 Micrometer 注册核心运行度量指标与任务拒绝计数
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class AtengThreadPoolMeterBinder implements MeterBinder, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (applicationContext == null) {
            log.warn("ateng-boot: ApplicationContext is null, skipping thread pool metrics binding");
            return;
        }

        Set<String> processedPoolNames = new HashSet<>();

        // 1. 扫描并绑定所有 Spring 受管 ThreadPoolTaskExecutor
        bindThreadPoolTaskExecutors(registry, processedPoolNames);

        // 2. 扫描并绑定未被包裹的原生 ThreadPoolExecutor Bean
        bindRawThreadPoolExecutors(registry, processedPoolNames);

        // 3. 扫描并绑定 SimpleAsyncTaskExecutor (支持虚拟线程)
        bindSimpleAsyncTaskExecutors(registry);
    }

    /**
     * 扫描并绑定所有 ThreadPoolTaskExecutor
     */
    private void bindThreadPoolTaskExecutors(MeterRegistry registry, Set<String> processedPoolNames) {
        Map<String, ThreadPoolTaskExecutor> taskExecutors = applicationContext.getBeansOfType(ThreadPoolTaskExecutor.class);
        for (Map.Entry<String, ThreadPoolTaskExecutor> entry : taskExecutors.entrySet()) {
            String beanName = entry.getKey();
            ThreadPoolTaskExecutor executor = entry.getValue();
            processedPoolNames.add(beanName);

            ThreadPoolExecutor tpe = null;
            try {
                tpe = executor.getThreadPoolExecutor();
            } catch (IllegalStateException ex) {
                try {
                    executor.initialize();
                    tpe = executor.getThreadPoolExecutor();
                } catch (Exception initEx) {
                    log.warn("ateng-boot: Failed to initialize ThreadPoolTaskExecutor [beanName={}]: {}", beanName, initEx.getMessage());
                }
            }

            // 注册核心线程数
            Gauge.builder("ateng.threadpool.core.size", executor, ThreadPoolTaskExecutor::getCorePoolSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The core number of threads")
                    .register(registry);

            // 注册最大线程数
            Gauge.builder("ateng.threadpool.max.size", executor, ThreadPoolTaskExecutor::getMaxPoolSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The maximum allowed number of threads")
                    .register(registry);

            // 注册当前活跃执行线程数
            Gauge.builder("ateng.threadpool.active.count", executor, ThreadPoolTaskExecutor::getActiveCount)
                    .tags("pool", beanName, "type", "platform")
                    .description("The approximate number of threads that are actively executing tasks")
                    .register(registry);

            // 注册当前工作线程总数
            Gauge.builder("ateng.threadpool.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The current number of threads in the pool")
                    .register(registry);

            // 注册当前队列排队积压任务数
            Gauge.builder("ateng.threadpool.queue.size", executor, ThreadPoolTaskExecutor::getQueueSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The current number of tasks queued in the executor")
                    .register(registry);

            if (tpe != null) {
                // 注册队列剩余容量
                Gauge.builder("ateng.threadpool.queue.remaining.capacity", tpe, e -> e.getQueue().remainingCapacity())
                        .tags("pool", beanName, "type", "platform")
                        .description("The remaining capacity of the queue")
                        .register(registry);

                // 注册累计完成任务总数
                FunctionCounter.builder("ateng.threadpool.completed.tasks.total", tpe, ThreadPoolExecutor::getCompletedTaskCount)
                        .tags("pool", beanName, "type", "platform")
                        .description("The approximate total number of tasks that have completed execution")
                        .register(registry);

                // 透明包装 RejectedExecutionHandler 统计任务拒绝数
                TrackingRejectedExecutionHandler trackingHandler = wrapAndSetRejectedExecutionHandler(tpe);
                FunctionCounter.builder("ateng.threadpool.rejected.tasks.total", trackingHandler, TrackingRejectedExecutionHandler::getRejectedCount)
                        .tags("pool", beanName, "type", "platform")
                        .description("The total number of tasks rejected by the executor")
                        .register(registry);
            }

            log.debug("ateng-boot: Bound metrics for ThreadPoolTaskExecutor [pool={}]", beanName);
        }
    }

    /**
     * 扫描并绑定未被包裹的原生 ThreadPoolExecutor Bean
     */
    private void bindRawThreadPoolExecutors(MeterRegistry registry, Set<String> processedPoolNames) {
        Map<String, ThreadPoolExecutor> executors = applicationContext.getBeansOfType(ThreadPoolExecutor.class);
        for (Map.Entry<String, ThreadPoolExecutor> entry : executors.entrySet()) {
            String beanName = entry.getKey();
            if (processedPoolNames.contains(beanName)) {
                continue;
            }
            ThreadPoolExecutor tpe = entry.getValue();

            Gauge.builder("ateng.threadpool.core.size", tpe, ThreadPoolExecutor::getCorePoolSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The core number of threads")
                    .register(registry);

            Gauge.builder("ateng.threadpool.max.size", tpe, ThreadPoolExecutor::getMaximumPoolSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The maximum allowed number of threads")
                    .register(registry);

            Gauge.builder("ateng.threadpool.active.count", tpe, ThreadPoolExecutor::getActiveCount)
                    .tags("pool", beanName, "type", "platform")
                    .description("The approximate number of threads that are actively executing tasks")
                    .register(registry);

            Gauge.builder("ateng.threadpool.pool.size", tpe, ThreadPoolExecutor::getPoolSize)
                    .tags("pool", beanName, "type", "platform")
                    .description("The current number of threads in the pool")
                    .register(registry);

            Gauge.builder("ateng.threadpool.queue.size", tpe, e -> e.getQueue().size())
                    .tags("pool", beanName, "type", "platform")
                    .description("The current number of tasks queued in the executor")
                    .register(registry);

            Gauge.builder("ateng.threadpool.queue.remaining.capacity", tpe, e -> e.getQueue().remainingCapacity())
                    .tags("pool", beanName, "type", "platform")
                    .description("The remaining capacity of the queue")
                    .register(registry);

            FunctionCounter.builder("ateng.threadpool.completed.tasks.total", tpe, ThreadPoolExecutor::getCompletedTaskCount)
                    .tags("pool", beanName, "type", "platform")
                    .description("The approximate total number of tasks that have completed execution")
                    .register(registry);

            TrackingRejectedExecutionHandler trackingHandler = wrapAndSetRejectedExecutionHandler(tpe);
            FunctionCounter.builder("ateng.threadpool.rejected.tasks.total", trackingHandler, TrackingRejectedExecutionHandler::getRejectedCount)
                    .tags("pool", beanName, "type", "platform")
                    .description("The total number of tasks rejected by the executor")
                    .register(registry);

            log.debug("ateng-boot: Bound metrics for raw ThreadPoolExecutor [pool={}]", beanName);
        }
    }

    /**
     * 扫描并绑定 SimpleAsyncTaskExecutor (虚拟线程 / 异步执行器)
     */
    private void bindSimpleAsyncTaskExecutors(MeterRegistry registry) {
        Map<String, SimpleAsyncTaskExecutor> asyncExecutors = applicationContext.getBeansOfType(SimpleAsyncTaskExecutor.class);
        for (Map.Entry<String, SimpleAsyncTaskExecutor> entry : asyncExecutors.entrySet()) {
            String beanName = entry.getKey();
            SimpleAsyncTaskExecutor executor = entry.getValue();

            Gauge.builder("ateng.threadpool.concurrency.limit", executor, SimpleAsyncTaskExecutor::getConcurrencyLimit)
                    .tags("pool", beanName, "type", "virtual")
                    .description("Concurrency limit of the simple async task executor")
                    .register(registry);

            Gauge.builder("ateng.threadpool.throttle.active", executor, ex -> ex.isThrottleActive() ? 1.0 : 0.0)
                    .tags("pool", beanName, "type", "virtual")
                    .description("Whether concurrency throttling is currently active")
                    .register(registry);

            Gauge.builder("ateng.threadpool.active", executor, ex -> ex.isActive() ? 1.0 : 0.0)
                    .tags("pool", beanName, "type", "virtual")
                    .description("Whether the simple async task executor is active")
                    .register(registry);

            log.debug("ateng-boot: Bound metrics for SimpleAsyncTaskExecutor [pool={}]", beanName);
        }
    }

    /**
     * 透明包装 ThreadPoolExecutor 的 RejectedExecutionHandler
     */
    private TrackingRejectedExecutionHandler wrapAndSetRejectedExecutionHandler(ThreadPoolExecutor tpe) {
        RejectedExecutionHandler current = tpe.getRejectedExecutionHandler();
        if (current instanceof TrackingRejectedExecutionHandler existing) {
            return existing;
        }
        TrackingRejectedExecutionHandler wrapped = TrackingRejectedExecutionHandler.wrap(current);
        tpe.setRejectedExecutionHandler(wrapped);
        return wrapped;
    }
}
