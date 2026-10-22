package io.github.atengk.boot.sample.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 云原生可观测性场景样例工程主启动类
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootApplication
public class SampleObservabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleObservabilityApplication.class, args);
    }

    /**
     * 自定义受管线程池 Bean，用于演示 AtengThreadPoolMeterBinder 自动发现与 Prometheus 度量指标暴露
     */
    @Bean(name = "sampleTaskExecutor")
    public ThreadPoolTaskExecutor sampleTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sample-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
