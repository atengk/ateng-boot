package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 临时业务令牌内部强类型载荷视图对象
 * 以纯净 JSON 字符串（零 @class）序列化存储于临时令牌后端存储中
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TempTokenTicketVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务场景标识
     */
    private String bizScene;

    /**
     * 关联目标业务资源标识
     */
    private String resourceId;

    /**
     * 业务资源名称描述
     */
    private String resourceName;

    /**
     * 签发申请人唯一标识
     */
    private String requesterId;
}
