package io.github.atengk.boot.core.aspect;

import io.github.atengk.boot.core.annotation.SlowAlert;
import io.github.atengk.boot.core.properties.SlowAlertProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢调用切面 SlowAlertAspect 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("慢调用切面 SlowAlertAspect 单测")
class SlowAlertAspectTest {

    private final SlowAlertProperties properties = new SlowAlertProperties();
    private final SlowAlertAspect aspect = new SlowAlertAspect(properties);

    @Test
    @DisplayName("方法级 @SlowAlert 慢调用触发与脱敏入参记录测试")
    void shouldInterceptSlowMethodAndPreserveReturn() throws Exception {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeSlow("atengUser", "secretPassword123");
        assertThat(result).isEqualTo("ok:atengUser");
    }

    @Test
    @DisplayName("快方法执行不产生超时告警")
    void shouldNotAlertOnFastMethod() {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeFast();
        assertThat(result).isEqualTo("fast");
    }

    @Test
    @DisplayName("配置 includeArgs 为 false 时安全执行")
    void shouldSupportDisableArgsSnapshot() throws Exception {
        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        proxy.executeWithoutArgs();
    }

    @Test
    @DisplayName("支持类级别 @SlowAlert 继承标注")
    void shouldSupportClassLevelAnnotation() throws Exception {
        ClassLevelService target = new ClassLevelService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        ClassLevelService proxy = factory.getProxy();

        proxy.doWork();
    }

    @Test
    @DisplayName("当全局开关 enabled 为 false 时，切面直接放行")
    void shouldBypassWhenDisabled() throws Exception {
        properties.setEnabled(false);

        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        SampleService proxy = factory.getProxy();

        String result = proxy.executeSlow("user", "pass");
        assertThat(result).isEqualTo("ok:user");
    }

    static class SampleService {

        @SlowAlert(value = "测试慢方法", threshold = 30)
        public String executeSlow(String username, String password) throws Exception {
            Thread.sleep(40);
            return "ok:" + username;
        }

        @SlowAlert(value = "测试快方法", threshold = 200)
        public String executeFast() {
            return "fast";
        }

        @SlowAlert(threshold = 30, includeArgs = false)
        public void executeWithoutArgs() throws Exception {
            Thread.sleep(40);
        }
    }

    @SlowAlert(threshold = 30)
    static class ClassLevelService {
        public void doWork() throws Exception {
            Thread.sleep(40);
        }
    }
}
