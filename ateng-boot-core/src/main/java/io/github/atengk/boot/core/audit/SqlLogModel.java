package io.github.atengk.boot.core.audit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.atengk.boot.core.enums.SqlTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化物理 SQL 操作审计日志元数据模型
 * 承载跨层（MyBatis 拦截器、异步线程池、事件总线、外部持久化 SPI）流转的 14 项核心 SQL 审计元数据契约
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SqlLogModel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全链路追踪唯一标识
     */
    private String traceId;

    /**
     * 租户唯一标识
     */
    private String tenantId;

    /**
     * 操作人唯一标识
     */
    private String userId;

    /**
     * 操作人姓名或账号
     */
    private String userName;

    /**
     * 数据源标识（如 master、slave 或物理数据源名称）
     */
    private String datasource;

    /**
     * MyBatis MappedStatement 唯一标识（Mapper 全限定类名.方法名）
     */
    private String statementId;

    /**
     * 实际物理执行的 SQL 语句（已完成参数格式化反填与脱敏）
     */
    private String sql;

    /**
     * SQL 操作动作类型（SELECT / INSERT / UPDATE / DELETE / OTHER）
     */
    private SqlTypeEnum sqlType;

    /**
     * 物理执行耗时（毫秒）
     */
    private Long costTime;

    /**
     * 影响行数（更新/删除/插入）或返回行数（查询）
     */
    private Long affectedRows;

    /**
     * 执行状态（0-成功，1-失败）
     */
    private Integer status;

    /**
     * 异常摘要信息或错误堆栈（仅在执行失败时非空）
     */
    private String errorMsg;

    /**
     * 业务调用来源代码位置（类名、方法名与行号）
     */
    private String callLocation;

    /**
     * SQL 操作执行时间戳
     */
    private LocalDateTime operateTime;

    /**
     * 扩展字典元数据
     */
    private Map<String, Object> extra;

    /**
     * 判定当前 SQL 操作是否执行成功
     *
     * @return 当 status 不为空且为 0 时返回 true，否则返回 false
     */
    @JsonIgnore
    public boolean isSuccess() {
        return this.status != null && this.status == 0;
    }

    /**
     * 便捷追加单个扩展属性键值对
     *
     * @param key   扩展属性键
     * @param value 扩展属性值
     * @return 当前 SqlLogModel 实例
     */
    public SqlLogModel addExtra(String key, Object value) {
        if (this.extra == null) {
            this.extra = new LinkedHashMap<>();
        }
        this.extra.put(key, value);
        return this;
    }
}
