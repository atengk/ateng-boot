package io.github.atengk.boot.core.audit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化操作审计日志元数据模型
 * 承载跨层（Web 切面、事件总线、持久层落库）流转的 18 项核心审计元数据契约
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OperateLogModel implements Serializable {

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
     * 操作人唯一标识（处于身份模拟态时为被模拟人 ID）
     */
    private String userId;

    /**
     * 真实原始操作人唯一标识（仅在处于身份模拟 RunAs 态时非空）
     */
    private String originalUserId;

    /**
     * 操作人姓名或账号
     */
    private String userName;

    /**
     * 功能模块名称（如“用户管理”）
     */
    private String module;

    /**
     * 操作动作类型
     */
    private OperateTypeEnum operateType;

    /**
     * 操作描述或业务说明
     */
    private String description;

    /**
     * HTTP 请求方式（如 GET、POST、PUT、DELETE）
     */
    private String requestMethod;

    /**
     * 请求 URL 路径或接口 URI
     */
    private String requestUrl;

    /**
     * 客户端真实 IP 地址
     */
    private String clientIp;

    /**
     * 操作地理位置/归属地（如“中国北京北京”）
     */
    private String operateLocation;

    /**
     * 请求入参（JSON 字符串或参数文本）
     */
    private String requestParams;

    /**
     * 响应出参（JSON 字符串或结果摘要）
     */
    private String responseResult;

    /**
     * 操作状态（0-成功，1-失败）
     */
    private Integer status;

    /**
     * 执行消耗时长（毫秒）
     */
    private Long costTime;

    /**
     * 异常摘要信息或错误堆栈
     */
    private String errorMsg;

    /**
     * 扩展字典
     */
    private Map<String, Object> extra;

    /**
     * 操作执行时间戳
     */
    private LocalDateTime operateTime;

    /**
     * 判定当前操作是否执行成功
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
     * @return 当前 OperateLogModel 实例
     */
    public OperateLogModel addExtra(String key, Object value) {
        if (this.extra == null) {
            this.extra = new LinkedHashMap<>();
        }
        this.extra.put(key, value);
        return this;
    }
}
