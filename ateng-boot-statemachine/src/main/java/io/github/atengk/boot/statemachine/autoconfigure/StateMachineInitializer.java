package io.github.atengk.boot.statemachine.autoconfigure;

import io.github.atengk.boot.statemachine.core.DefaultStateMachine;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import io.github.atengk.boot.statemachine.core.StateMachineInterceptor;
import io.github.atengk.boot.statemachine.core.StateTransitionAuditListener;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Spring 容器状态机生命周期初始化与桥接处理器
 * <p>负责在单例初始化完毕后，自动向所有状态机注入受管切面拦截器与轨迹审计监听器，并注册至全局 {@link StateMachineFactory}</p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class StateMachineInitializer implements SmartInitializingSingleton, ApplicationContextAware, DisposableBean {

    private ApplicationContext applicationContext;
    private final List<StateMachineInterceptor<?, ?, ?>> interceptors;
    private final List<StateTransitionAuditListener<?, ?, ?>> auditListeners;
    private final Set<String> registeredMachineIds = new CopyOnWriteArraySet<>();

    public StateMachineInitializer(List<StateMachineInterceptor<?, ?, ?>> interceptors,
                                   List<StateTransitionAuditListener<?, ?, ?>> auditListeners) {
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
        this.auditListeners = auditListeners != null ? auditListeners : Collections.emptyList();
    }

    public StateMachineInitializer(List<StateMachineInterceptor<?, ?, ?>> interceptors) {
        this(interceptors, Collections.emptyList());
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void afterSingletonsInstantiated() {
        if (applicationContext == null) {
            return;
        }

        Map<String, StateMachine> stateMachineBeans = applicationContext.getBeansOfType(StateMachine.class);
        Set<String> seenIds = new HashSet<>();

        for (StateMachine machine : stateMachineBeans.values()) {
            String machineId = machine.getMachineId();
            if (!seenIds.add(machineId)) {
                throw new StateMachineException(
                        StateMachineResultCode.DUPLICATE_MACHINE_ID,
                        String.format("状态机标识 [%s] 重复注册冲突", machineId)
                );
            }

            // 1. 若为 DefaultStateMachine，注入受管切面拦截器与轨迹审计监听器
            if (machine instanceof DefaultStateMachine defaultStateMachine) {
                if (interceptors != null) {
                    for (StateMachineInterceptor interceptor : interceptors) {
                        defaultStateMachine.addInterceptor(interceptor);
                    }
                }
                if (auditListeners != null) {
                    for (StateTransitionAuditListener auditListener : auditListeners) {
                        defaultStateMachine.addAuditListener(auditListener);
                    }
                }
                Map<String, StateTransitionAuditListener> ctxAuditListeners = applicationContext.getBeansOfType(StateTransitionAuditListener.class);
                for (StateTransitionAuditListener listener : ctxAuditListeners.values()) {
                    defaultStateMachine.addAuditListener(listener);
                }
            }

            // 2. 自动注册至全局 StateMachineFactory
            StateMachineFactory.registerOrUpdate(machine);
            registeredMachineIds.add(machineId);
        }
    }

    @Override
    public void destroy() {
        for (String machineId : registeredMachineIds) {
            StateMachineFactory.unregister(machineId);
        }
        registeredMachineIds.clear();
    }
}
