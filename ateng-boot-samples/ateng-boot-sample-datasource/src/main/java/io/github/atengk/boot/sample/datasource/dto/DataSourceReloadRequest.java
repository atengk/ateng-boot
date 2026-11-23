package io.github.atengk.boot.sample.datasource.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态数据源无损热重载请求传输对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceReloadRequest {

    /**
     * 待热重载的数据源唯一标识
     */
    @NotBlank(message = "数据源键名不能为空")
    private String dsKey;

    /**
     * 新的 JDBC 物理连接地址
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String url;

    /**
     * 新的数据库用户名
     */
    @Builder.Default
    private String username = "sa";

    /**
     * 新的数据库密码（支持明文或 enc:sm4:/enc:aes: 密文）
     */
    @Builder.Default
    private String password = "";

    /**
     * 新的 JDBC 驱动类名
     */
    @Builder.Default
    private String driverClassName = "org.h2.Driver";

    /**
     * 是否对新连接池执行并发预热
     */
    @Builder.Default
    private boolean preWarm = false;

    /**
     * 并发预热目标物理连接数
     */
    @NotNull(message = "预热连接数不能为空")
    @Min(value = 1, message = "预热连接数必须大于等于 1")
    @Builder.Default
    private Integer preWarmConnections = 5;

    /**
     * 是否对旧连接池执行超时优雅排空
     */
    @Builder.Default
    private boolean graceful = true;
}
