package io.github.atengk.boot.sample.ai.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 知识库文档级联删除响应载体
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class AiRagDocumentDeleteVo {

    /**
     * 文档业务唯一标识
     */
    String docId;

    /**
     * 是否执行成功
     */
    boolean deleted;

    /**
     * 响应描述消息
     */
    String message;
}
