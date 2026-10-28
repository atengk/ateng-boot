package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局状态机注册与获取工厂门面
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class StateMachineFactory {

    private static final Map<String, StateMachine<?, ?, ?>> REGISTRY = new ConcurrentHashMap<>();

    private StateMachineFactory() {
    }

    /**
     * 注册状态机实例至全局工厂
     *
     * @param stateMachine 状态机实例
     * @throws StateMachineException 若 machineId 已存在则抛出 DUPLICATE_MACHINE_ID 异常
     */
    public static void register(StateMachine<?, ?, ?> stateMachine) {
        if (stateMachine == null) {
            throw new IllegalArgumentException("StateMachine must not be null");
        }
        String machineId = stateMachine.getMachineId();
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }

        StateMachine<?, ?, ?> existing = REGISTRY.putIfAbsent(machineId, stateMachine);
        if (existing != null && existing != stateMachine) {
            throw new StateMachineException(
                    StateMachineResultCode.DUPLICATE_MACHINE_ID,
                    String.format("状态机标识 [%s] 重复注册冲突", machineId)
            );
        }
    }

    /**
     * 注册或更新状态机实例至全局工厂
     *
     * @param stateMachine 状态机实例
     */
    public static void registerOrUpdate(StateMachine<?, ?, ?> stateMachine) {
        if (stateMachine == null) {
            throw new IllegalArgumentException("StateMachine must not be null");
        }
        String machineId = stateMachine.getMachineId();
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }
        REGISTRY.put(machineId, stateMachine);
    }

    /**
     * 根据 machineId 获取强类型状态机实例
     *
     * @param machineId 状态机唯一标识
     * @param <S>       状态类型
     * @param <E>       事件类型
     * @param <C>       上下文类型
     * @return 状态机实例
     * @throws StateMachineException 若状态机未注册则抛出异常
     */
    @SuppressWarnings("unchecked")
    public static <S, E, C> StateMachine<S, E, C> get(String machineId) {
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }
        StateMachine<?, ?, ?> machine = REGISTRY.get(machineId);
        if (machine == null) {
            throw new StateMachineException(
                    StateMachineResultCode.MACHINE_NOT_FOUND,
                    String.format("全局状态机工厂未找到标识为 [%s] 的状态机实例", machineId)
            );
        }
        return (StateMachine<S, E, C>) machine;
    }

    /**
     * 判断是否已注册指定状态机
     *
     * @param machineId 状态机唯一标识
     * @return true 若已注册，否则 false
     */
    public static boolean contains(String machineId) {
        return machineId != null && REGISTRY.containsKey(machineId);
    }

    /**
     * 注销指定状态机实例
     *
     * @param machineId 状态机唯一标识
     * @return 被注销的状态机实例，若不存在返回 null
     */
    public static StateMachine<?, ?, ?> unregister(String machineId) {
        return machineId != null ? REGISTRY.remove(machineId) : null;
    }

    /**
     * 清理所有已注册的状态机（供单元测试重置现场使用）
     */
    public static void clear() {
        REGISTRY.clear();
    }
}
