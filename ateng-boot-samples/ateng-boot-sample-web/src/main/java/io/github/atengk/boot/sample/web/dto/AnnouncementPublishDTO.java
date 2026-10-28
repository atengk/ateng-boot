package io.github.atengk.boot.sample.web.dto;

import io.github.atengk.boot.web.xss.annotation.XssIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 演示全站公告发布入参 DTO
 * 演示类级别标注 {@link XssIgnore}，对整个 DTO 内部所有字符串字段实施批量 XSS 清洗豁免
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@XssIgnore
public class AnnouncementPublishDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 公告标题
     */
    @NotBlank(message = "公告标题不能为空")
    private String title;

    /**
     * 公告富文本内容
     */
    @NotBlank(message = "公告内容不能为空")
    private String content;
}
