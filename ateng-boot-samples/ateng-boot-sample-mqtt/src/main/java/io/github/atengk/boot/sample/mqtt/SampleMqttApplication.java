package io.github.atengk.boot.sample.mqtt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MQTT 工业物联网与车联网双向轻量通信集成样例主启动类
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootApplication
public class SampleMqttApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleMqttApplication.class, args);
    }
}
