package io.github.atengk.boot.sample.job.xxl;

import io.github.atengk.boot.job.xxl.tenant.TenantListProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * XXL-Job 分布式任务调度场景样例启动类
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootApplication
public class SampleJobXxlApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleJobXxlApplication.class, args);
    }

    /**
     * 注册样例多租户提供者 SPI，供 TenantJobTemplate 租户遍历执行
     */
    @Bean
    public TenantListProvider sampleTenantListProvider() {
        return () -> List.of("tenant-1001", "tenant-1002", "tenant-1003");
    }
}
