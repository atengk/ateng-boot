package io.github.atengk.boot.mybatisplus.dynamicsql;

import org.apache.ibatis.mapping.SqlCommandType;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 外部动态 SQL 元数据契约实体
 * 承载 SQL 唯一标识（StatementId）、XML 脚本片段、命令类型与目标结果类型映射
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class DynamicSqlDefinition {

    /**
     * 常见 MyBatis 动态 SQL 标签特征集
     */
    private static final Set<String> DYNAMIC_TAGS = Set.of(
            "<where", "<if", "<foreach", "<choose", "<when", "<otherwise", "<trim", "<set", "<bind"
    );

    /**
     * 全局唯一 StatementId（例如 com.example.mapper.UserMapper.selectDynamicUsers 或 rpt_user_growth）
     */
    private final String id;

    /**
     * XML 格式的 SQL 脚本文本
     */
    private final String sqlScript;

    /**
     * SQL 命令类型（SELECT, INSERT, UPDATE, DELETE）
     */
    private final SqlCommandType commandType;

    /**
     * 目标返回结果类型（默认为 Map.class）
     */
    private final Class<?> resultType;

    /**
     * 语义化注释与业务说明
     */
    private final String description;

    /**
     * 是否开启智能容错包裹 <script> 根标签（默认 true）
     */
    private final boolean autoScriptWrap;

    /**
     * SQL SELECT AST 投影映射字典（归一化属性名 -> 物理表别名/列名）
     */
    private final Map<String, String> projections;

    private DynamicSqlDefinition(Builder builder) {
        Assert.hasText(builder.id, "Dynamic SQL id must not be blank");
        Assert.hasText(builder.sqlScript, "Dynamic SQL script must not be blank");

        this.id = builder.id.trim();
        this.commandType = (builder.commandType != null) ? builder.commandType : SqlCommandType.SELECT;
        this.resultType = (builder.resultType != null) ? builder.resultType : Map.class;
        this.description = builder.description;
        this.autoScriptWrap = builder.autoScriptWrap;
        this.sqlScript = wrapScriptIfNecessary(builder.sqlScript, builder.autoScriptWrap);
        this.projections = (builder.projections != null) ? Map.copyOf(builder.projections) : java.util.Collections.emptyMap();
    }

    /**
     * 智能容错包裹 <script> 根标签
     * 若 SQL 包含动态标签且首尾未显式包裹 <script>...</script> 时自动包裹
     *
     * @param sql            原始 SQL 脚本文本
     * @param autoScriptWrap 是否开启容错包裹
     * @return 最终 SQL 脚本文本
     */
    public static String wrapScriptIfNecessary(String sql, boolean autoScriptWrap) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        if (!autoScriptWrap) {
            return sql;
        }
        if (trimmed.startsWith("<script>") && trimmed.endsWith("</script>")) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase();
        boolean containsDynamicTag = DYNAMIC_TAGS.stream().anyMatch(lower::contains);
        if (containsDynamicTag) {
            return "<script>\n" + trimmed + "\n</script>";
        }
        return trimmed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getSqlScript() {
        return sqlScript;
    }

    public SqlCommandType getCommandType() {
        return commandType;
    }

    public Class<?> getResultType() {
        return resultType;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAutoScriptWrap() {
        return autoScriptWrap;
    }

    public Map<String, String> getProjections() {
        return projections != null ? projections : java.util.Collections.emptyMap();
    }

    /**
     * 衍生绑定了 AST 投影字典的新 Definition 实例
     *
     * @param projections 归一化 AST 投影映射字典
     * @return 挂载了投影字典的全新不可变实例
     */
    public DynamicSqlDefinition withProjections(Map<String, String> projections) {
        return builder()
                .id(this.id)
                .sqlScript(this.sqlScript)
                .commandType(this.commandType)
                .resultType(this.resultType)
                .description(this.description)
                .autoScriptWrap(false)
                .projections(projections)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DynamicSqlDefinition that = (DynamicSqlDefinition) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "DynamicSqlDefinition{" +
                "id='" + id + '\'' +
                ", commandType=" + commandType +
                ", resultType=" + (resultType != null ? resultType.getName() : "null") +
                ", description='" + description + '\'' +
                ", autoScriptWrap=" + autoScriptWrap +
                ", projectionsCount=" + (projections != null ? projections.size() : 0) +
                '}';
    }

    /**
     * 构建器
     */
    public static class Builder {
        private String id;
        private String sqlScript;
        private SqlCommandType commandType;
        private Class<?> resultType;
        private String description;
        private boolean autoScriptWrap = true;
        private Map<String, String> projections;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder sqlScript(String sqlScript) {
            this.sqlScript = sqlScript;
            return this;
        }

        public Builder commandType(SqlCommandType commandType) {
            this.commandType = commandType;
            return this;
        }

        public Builder resultType(Class<?> resultType) {
            this.resultType = resultType;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder autoScriptWrap(boolean autoScriptWrap) {
            this.autoScriptWrap = autoScriptWrap;
            return this;
        }

        public Builder projections(Map<String, String> projections) {
            this.projections = projections;
            return this;
        }

        public DynamicSqlDefinition build() {
            return new DynamicSqlDefinition(this);
        }
    }
}
