package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.http.annotation.EnableHttpInterfaces;
import io.github.atengk.boot.http.customizer.HttpClient5Customizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * HTTP 声明式与命令式通信底座自验集成样例应用启动类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@EnableHttpInterfaces(basePackages = "io.github.atengk.boot.sample.http.client")
@SpringBootApplication
public class SampleHttpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleHttpApplication.class, args);
    }

    /**
     * 演示并通过集成测试验证 HttpClient5Customizer SPI 扩展机制
     */
    @Bean
    public HttpClient5Customizer sampleHttpClient5Customizer() {
        return (clientBuilder, connectionManagerBuilder) -> {
            clientBuilder.setUserAgent("ateng-sample-agent/1.0.0");
        };
    }

    /**
     * 演示并通过集成测试验证 Spring RestClientCustomizer 定制机制
     */
    @Bean
    public RestClientCustomizer sampleRestClientCustomizer() {
        return restClientBuilder -> restClientBuilder.defaultHeader("X-Custom-Client", "ateng-http-sample");
    }
}
