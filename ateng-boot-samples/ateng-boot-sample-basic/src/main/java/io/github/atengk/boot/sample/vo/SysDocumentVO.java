package io.github.atengk.boot.sample.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务文档展示视图对象 (VO)
 * 传输边界专用对外暴露契约，与持久层 SysDocumentEntity 彻底解耦 (Zero-Entity-Leakage)
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@ToString
public class SysDocumentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一标识
     */
    private Long id;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 所属部门 ID
     */
    private Long deptId;

    /**
     * 所属用户 ID
     */
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
