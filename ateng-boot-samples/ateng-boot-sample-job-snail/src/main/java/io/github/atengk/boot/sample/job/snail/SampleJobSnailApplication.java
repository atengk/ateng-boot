package io.github.atengk.boot.sample.job.snail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SnailJob 调度与重试双擎组件场景自验工程启动类
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootApplication
public class SampleJobSnailApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleJobSnailApplication.class, args);
    }
}
