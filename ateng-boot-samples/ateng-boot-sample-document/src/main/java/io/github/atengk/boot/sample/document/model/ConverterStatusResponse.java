package io.github.atengk.boot.sample.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档跨格式转换器运行时状态视图对象
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConverterStatusResponse {

    /**
     * 当前生效的转换 SPI 实现类名称
     */
    private String converterClass;

    /**
     * 当前运行模式（OFFLINE_SIMULATED / GOTENBERG / DEFAULT）
     */
    private String mode;

    /**
     * Gotenberg 远程适配器配置开关状态
     */
    private boolean gotenbergEnabled;
}
