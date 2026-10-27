package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.handler.AtengJacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON 字段映射演示实体
 * 验证 List、Set、Map 与嵌套复杂 POJO（含 LocalDateTime）的结构化存储与空安全保底
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "demo_json_user", autoResultMap = true)
public class DemoJsonUserEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户名
     */
    private String username;

    /**
     * 标签列表（空值保底返回空 List）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 角色标识集合（空值保底返回空 Set）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private Set<String> roles;

    /**
     * 扩展字典键值对（空值保底返回空 Map）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private Map<String, Object> extraInfo;

    /**
     * 嵌套复杂用户画像（包含 LocalDateTime JSR-310 时间字段）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private UserProfile profile;

    public DemoJsonUserEntity(String username) {
        this.username = username;
    }

    /**
     * 嵌套复杂画像模型
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile implements Serializable {
        private static final long serialVersionUID = 1L;

        private String bio;
        private Integer level;
        private LocalDateTime registerTime;
    }
}
