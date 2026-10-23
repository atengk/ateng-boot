package io.github.atengk.boot.sample.pay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业级多渠道支付专有收单与 Webhook 回调场景自验主启动类
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootApplication
public class SamplePayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SamplePayApplication.class, args);
    }
}
