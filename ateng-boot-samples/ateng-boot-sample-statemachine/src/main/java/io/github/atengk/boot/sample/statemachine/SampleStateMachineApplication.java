package io.github.atengk.boot.sample.statemachine;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 轻量无状态状态机电商场景样例主启动类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.statemachine.mapper")
public class SampleStateMachineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleStateMachineApplication.class, args);
    }
}
