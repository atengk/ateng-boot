package io.github.atengk.boot.sample.job.local;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工业级单机定时任务与轻量调度基础设施自验集成样例应用
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootApplication
public class SampleJobLocalApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleJobLocalApplication.class, args);
    }
}
