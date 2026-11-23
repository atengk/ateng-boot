package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 带有动态请求选项的用户查询请求对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOptionsRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标用户唯一标识
     */
    private Long id;

    /**
     * 单次读取超时时间（毫秒），覆盖全局配置
     */
    private Long readTimeoutMillis;

    /**
     * 自定义下发请求头内容
     */
    private String customHeader;

    /**
     * 自定义下发查询参数
     */
    private String customQueryParam;
}
