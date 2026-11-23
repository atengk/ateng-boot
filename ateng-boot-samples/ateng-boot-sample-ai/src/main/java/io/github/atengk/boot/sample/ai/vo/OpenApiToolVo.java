package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * OpenAPI 受管工具元数据视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiToolVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 操作标识 operationId
     */
    private String operationId;

    /**
     * HTTP 请求方法
     */
    private String httpMethod;

    /**
     * 路径模板
     */
    private String path;

    /**
     * 操作摘要
     */
    private String summary;

    /**
     * 详细说明
     */
    private String description;

    /**
     * 参数定义列表
     */
    private List<String> parameters;
}
