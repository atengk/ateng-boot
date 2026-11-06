package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * OpenAPI 挂载工具对话响应视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiChatVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 大模型响应正文
     */
    private String response;

    /**
     * 当前挂载的可用 OpenAPI 工具清单
     */
    private List<String> toolsAvailable;
}
