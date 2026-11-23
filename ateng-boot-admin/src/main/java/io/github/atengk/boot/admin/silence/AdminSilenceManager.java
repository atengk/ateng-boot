package io.github.atengk.boot.admin.silence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Spring Boot Admin 运行时动态维护静默窗口管理契约 SPI
 * <p>
 * 支持按服务通配符、持续时长与维护原因在运行期动态创建、撤销、清理及只读列举维护静默规则，
 * 与静态配置黑白名单过滤正交解耦。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface AdminSilenceManager {

    /**
     * 创建一条动态维护静默规则
     *
     * @param servicePattern 服务名或通配符模式（如 {@code order-*}）
     * @param duration       静默持续时长（传入 {@code null} 时使用系统默认维护静默期，必须为正数）
     * @param reason         维护操作原因说明
     * @param operator       操作人标识
     * @return 新创建并已持久化的静默规则模型
     */
    SilenceRule createSilence(String servicePattern, Duration duration, String reason, String operator);

    /**
     * 按规则 ID 主动撤销一条维护静默规则
     *
     * @param ruleId 规则唯一标识
     * @return 若对应规则存在且已移除返回 {@code true}，未命中返回 {@code false}
     */
    boolean revokeSilence(String ruleId);

    /**
     * 判断指定服务当前是否处于任意一条有效的维护静默窗口内
     *
     * @param serviceName 目标实例所属服务名
     * @return 命中有效静默规则时返回 {@code true}
     */
    boolean isSilenced(String serviceName);

    /**
     * 查询当前命中目标服务的首条有效维护静默规则
     *
     * @param serviceName 目标实例所属服务名
     * @return 命中的有效静默规则（未命中返回 {@link Optional#empty()}）
     */
    Optional<SilenceRule> findMatchingRule(String serviceName);

    /**
     * 列举当前全部未过期的维护静默规则只读列表
     *
     * @return 有效静默规则列表（永不返回 null）
     */
    List<SilenceRule> listActiveSilences();

    /**
     * 触发清理已过期失效的静默规则并统计剩余有效数量变化
     *
     * @return 本次清理操作清理出的过期规则数
     */
    int cleanExpiredSilences();
}
