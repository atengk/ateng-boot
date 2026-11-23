package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库文档级联删除请求入参
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagDocumentDeleteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待物理级联删除的文档业务标识 docId
     */
    @NotBlank(message = "待删除文档标识 docId 不能为空")
    private String docId;
}
