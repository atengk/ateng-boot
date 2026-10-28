package io.github.atengk.boot.sample.web.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户版本信息展示视图对象
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserVersionInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * API 版本号
     */
    private String version;

    /**
     * 用户姓名
     */
    private String name;

    /**
     * 会员等级（V2 专有字段）
     */
    private String level;

    /**
     * 契约模式
     */
    private String contract;
}
