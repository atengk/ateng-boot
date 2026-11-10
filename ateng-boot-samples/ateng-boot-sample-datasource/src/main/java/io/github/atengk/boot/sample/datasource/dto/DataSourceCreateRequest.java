package io.github.atengk.boot.sample.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态数据源创建请求传输对象
 * <p>
 * 遵循 Zero-Entity-Leakage 契约，严格解耦第三方持久层与框架属性。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceCreateRequest {

    /**
     * 数据源唯一键名
     */
    @NotBlank(message = "数据源键名不能为空")
    private String dsKey;

    /**
     * JDBC 物理连接地址
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String url;

    /**
     * 数据库用户名
     */
    @Builder.Default
    private String username = "sa";

    /**
     * 数据库密码
     */
    @Builder.Default
    private String password = "";

    /**
     * JDBC 驱动类名
     */
    @Builder.Default
    private String driverClassName = "org.h2.Driver";

    /**
     * 挂载后是否立即并发预热物理连接
     */
    @Builder.Default
    private boolean preWarm = false;

    /**
     * 预热物理连接数
     */
    @Builder.Default
    private Integer preWarmConnections = 5;
}
