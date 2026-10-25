package io.github.atengk.boot.statemachine.autoconfigure;

import io.github.atengk.boot.statemachine.core.DefaultStateMachine;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import io.github.atengk.boot.statemachine.core.StateMachineInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.List;
import java.util.Map;

/**
 * Spring 容器状态机生命周期初始化与桥接处理器
 * <p>负责在单例初始化完毕后，自动向所有状态机注入受管切面拦截器，并注册至全局 {@link StateMachineFactory}</p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class StateMachineInitializer implements SmartInitializingSingleton, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private final List<StateMachineInterceptor<?, ?, ?>> interceptors;

    public StateMachineInitializer(List<StateMachineInterceptor<?, ?, ?>> interceptors) {
        this.interceptors = interceptors;
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
        for (StateMachine machine : stateMachineBeans.values()) {
            // 1. 若为 DefaultStateMachine 且存在切面拦截器，注入拦截器
            if (machine instanceof DefaultStateMachine defaultStateMachine && interceptors != null) {
                for (StateMachineInterceptor interceptor : interceptors) {
                    defaultStateMachine.addInterceptor(interceptor);
                }
            }

            // 2. 自动注册至全局 StateMachineFactory（若未注册）
            if (!StateMachineFactory.contains(machine.getMachineId())) {
                StateMachineFactory.register(machine);
            }
        }
    }
}
