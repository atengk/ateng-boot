package io.github.atengk.boot.admin.admission;

import lombok.Getter;
import lombok.ToString;
import org.springframework.http.HttpStatus;

import java.io.Serializable;

/**
 * 客户端实例注册准入评估结果领域模型
 * 承载准入决策状态、HTTP 状态码及人类可读拒绝成因
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@ToString
public final class AdminAdmissionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否允许准入放行
     */
    private final boolean allowed;

    /**
     * HTTP 响应状态码（放行默认为 200 OK，拒绝默认 403 FORBIDDEN）
     */
    private final HttpStatus status;

    /**
     * 准入拒绝原因详情说明（放行时为 null）
     */
    private final String reason;

    private AdminAdmissionResult(boolean allowed, HttpStatus status, String reason) {
        this.allowed = allowed;
        this.status = status != null ? status : (allowed ? HttpStatus.OK : HttpStatus.FORBIDDEN);
        this.reason = reason;
    }

    /**
     * 构造准入放行评估结果
     *
     * @return 准入放行结果
     */
    public static AdminAdmissionResult admit() {
        return new AdminAdmissionResult(true, HttpStatus.OK, null);
    }

    /**
     * 构造准入拒绝评估结果（默认 HTTP 403 Forbidden）
     *
     * @param reason 拒绝原因说明
     * @return 准入拒绝结果
     */
    public static AdminAdmissionResult reject(String reason) {
        return new AdminAdmissionResult(false, HttpStatus.FORBIDDEN, reason);
    }

    /**
     * 构造携带显式 HTTP 状态码的准入拒绝评估结果
     *
     * @param status 目标 HTTP 状态码
     * @param reason 拒绝原因说明
     * @return 准入拒绝结果
     */
    public static AdminAdmissionResult reject(HttpStatus status, String reason) {
        return new AdminAdmissionResult(false, status, reason);
    }

    /**
     * 是否已被阻断拒绝
     */
    public boolean isRejected() {
        return !this.allowed;
    }
}
