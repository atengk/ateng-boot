package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 外部动态 SQL 运行时热注册与原子热替换请求载荷
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class DynamicSqlHotSwapDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 动态 SQL 语句唯一标识（如 custom.hotSwapDemo）
     */
    @NotBlank(message = "语句唯一标识 statementId 不能为空")
    private String statementId;

    /**
     * MyBatis 动态 SQL 脚本内容（支持 <where>、<if> 等标签）
     */
    @NotBlank(message = "动态 SQL 脚本内容 sqlScript 不能为空")
    private String sqlScript;

    /**
     * 语句用途说明
     */
    private String description;

    /**
     * 热更新成功后立即验证执行的测试参数（可选）
     */
    private Map<String, Object> sampleParams;
}
