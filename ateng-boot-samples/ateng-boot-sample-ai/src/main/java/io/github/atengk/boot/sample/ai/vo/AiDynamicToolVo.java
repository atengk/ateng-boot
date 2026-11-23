package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 动态函数调用响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDynamicToolVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户提问
     */
    private String question;

    /**
     * 最终回复
     */
    private String answer;

    /**
     * 调用过程执行的工具函数列表
     */
    @Builder.Default
    private List<String> toolsUsed = Collections.emptyList();
}
