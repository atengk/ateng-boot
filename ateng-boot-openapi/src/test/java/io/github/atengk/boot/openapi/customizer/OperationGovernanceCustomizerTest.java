package io.github.atengk.boot.openapi.customizer;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块接口治理契约内省定制器测试
 *
 * @author Ateng
 * @since 2026-11-16
 */
@DisplayName("OperationGovernanceCustomizer 跨模块治理契约测试")
class OperationGovernanceCustomizerTest {

    static class NormalController {
        @SaCheckLogin
        @SaCheckRole(value = {"admin", "auditor"}, mode = SaMode.OR)
        @SaCheckPermission(value = {"user:create"}, mode = SaMode.AND)
        public void securedAction() {
        }

        @SaIgnore
        public void publicAction() {
        }

        @SaIgnore
        @SaCheckLogin
        public void conflictedMethodAuth() {
        }
    }

    @SaIgnore
    static class IgnoredClassController {
        public void openEndpoint() {
        }

        @SaCheckLogin
        @SaCheckPermission("admin:override")
        public void methodOverrideAuth() {
        }
    }

    @Test
    @DisplayName("测试 @SaIgnore 公开方法自动摘除锁头（security 置空列表）且在缺省 Web 依赖环境下安全降级")
    void testSaIgnoreClearsSecurityAndSafeWithoutWebAnnotations() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);

        Operation operation = new Operation()
                .description("公开健康探针")
                .security(List.of(new SecurityRequirement().addList("satoken")));

        Method method = NormalController.class.getMethod("publicAction");
        HandlerMethod handlerMethod = new HandlerMethod(new NormalController(), method);

        customizer.customize(operation, handlerMethod);

        assertThat(operation.getSecurity()).isNotNull().isEmpty();
        assertThat(operation.getDescription()).contains("免鉴权公开访问");
    }

    @Test
    @DisplayName("测试类级 @SaIgnore 与方法级 @SaCheckLogin 覆盖仲裁：方法鉴权覆盖时保留锁头")
    void testMethodAuthOverridesClassSaIgnore() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);
        IgnoredClassController controller = new IgnoredClassController();

        // 1. 继承类级 @SaIgnore -> 摘除锁头
        Operation openOp = new Operation().security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(openOp, new HandlerMethod(controller, IgnoredClassController.class.getMethod("openEndpoint")));
        assertThat(openOp.getSecurity()).isEmpty();

        // 2. 方法显式标注 @SaCheckLogin -> 不摘除锁头，并追加权限说明
        Operation overrideOp = new Operation().security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(overrideOp, new HandlerMethod(controller, IgnoredClassController.class.getMethod("methodOverrideAuth")));
        assertThat(overrideOp.getSecurity()).hasSize(1);
        assertThat(overrideOp.getDescription())
                .contains("**接口治理契约**：")
                .contains("admin:override");

        // 3. 同方法叠标 @SaIgnore 与 @SaCheckLogin -> Fail-Closed 保留锁头
        Operation conflictedOp = new Operation().security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(conflictedOp, new HandlerMethod(new NormalController(), NormalController.class.getMethod("conflictedMethodAuth")));
        assertThat(conflictedOp.getSecurity()).hasSize(1);
        assertThat(conflictedOp.getDescription()).contains("需要登录认证");
    }

    @Test
    @DisplayName("测试 Sa-Token 登录、角色与权限注解渲染标准化 Markdown 治理说明及幂等防重")
    void testSaTokenGovernanceNotesIdempotent() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);

        Operation operation = new Operation().description("创建系统用户");
        HandlerMethod handlerMethod = new HandlerMethod(
                new NormalController(),
                NormalController.class.getMethod("securedAction")
        );

        customizer.customize(operation, handlerMethod);
        String firstDesc = operation.getDescription();
        assertThat(firstDesc)
                .contains("创建系统用户")
                .contains("**接口治理契约**：")
                .contains("登录认证")
                .contains("角色要求")
                .contains("admin")
                .contains("auditor")
                .contains("权限要求")
                .contains("user:create");

        // 重复调用幂等，不重复追加
        customizer.customize(operation, handlerMethod);
        assertThat(operation.getDescription()).isEqualTo(firstDesc);
    }

    @Test
    @DisplayName("测试关闭 operationGovernanceNotes 开关时跳过描述说明注入，仍保留 @SaIgnore 免锁头语义")
    void testGovernanceNotesDisabledSwitch() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setOperationGovernanceNotes(false);
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);

        Operation publicOp = new Operation()
                .description("原始描述")
                .security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(publicOp, new HandlerMethod(
                new NormalController(), NormalController.class.getMethod("publicAction")));
        assertThat(publicOp.getSecurity()).isEmpty();
        assertThat(publicOp.getDescription()).isEqualTo("原始描述");

        Operation securedOp = new Operation().description("受保护描述");
        customizer.customize(securedOp, new HandlerMethod(
                new NormalController(), NormalController.class.getMethod("securedAction")));
        assertThat(securedOp.getDescription()).isEqualTo("受保护描述");
    }

    @Test
    @DisplayName("测试空安全防御保护")
    void testNullSafety() {
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(new OpenApiProperties());
        assertThat(customizer.customize(null, null)).isNull();

        Operation op = new Operation().description("demo");
        assertThat(customizer.customize(op, null)).isSameAs(op);
        assertThat(op.getDescription()).isEqualTo("demo");
    }

    @Test
    @DisplayName("测试 8 类全量治理注解组合（含 Web 5 类注解）与手写 X-Signature 验签头防重保留")
    @SuppressWarnings("unchecked")
    void testAllEightGovernanceAnnotationsAndCustomSignaturePreserved() throws Exception {
        ClassLoader parentCl = getClass().getClassLoader();
        ByteBuddy byteBuddy = new ByteBuddy();

        AnnotationDescription runtimeRetention = AnnotationDescription.Builder.ofType(Retention.class)
                .define("value", RetentionPolicy.RUNTIME)
                .build();
        AnnotationDescription methodAndTypeTarget = AnnotationDescription.Builder.ofType(Target.class)
                .defineEnumerationArray("value", ElementType.class, ElementType.METHOD, ElementType.TYPE)
                .build();

        // 动态在独立子 ClassLoader 中生成 5 个 Web 治理注解桩
        Class<? extends Annotation> idempotentAnn = (Class<? extends Annotation>) byteBuddy
                .makeAnnotation()
                .name("io.github.atengk.boot.web.idempotent.annotation.Idempotent")
                .annotateType(runtimeRetention, methodAndTypeTarget)
                .defineMethod("timeout", long.class, Visibility.PUBLIC).defaultValue(3L, long.class)
                .defineMethod("timeUnit", TimeUnit.class, Visibility.PUBLIC).defaultValue(TimeUnit.SECONDS, TimeUnit.class)
                .make()
                .load(parentCl, ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        ClassLoader dynamicCl = idempotentAnn.getClassLoader();

        Class<? extends Annotation> rateLimiterAnn = (Class<? extends Annotation>) byteBuddy
                .makeAnnotation()
                .name("io.github.atengk.boot.web.ratelimit.annotation.RateLimiter")
                .annotateType(runtimeRetention, methodAndTypeTarget)
                .defineMethod("count", long.class, Visibility.PUBLIC).defaultValue(100L, long.class)
                .defineMethod("time", long.class, Visibility.PUBLIC).defaultValue(60L, long.class)
                .defineMethod("timeUnit", TimeUnit.class, Visibility.PUBLIC).defaultValue(TimeUnit.SECONDS, TimeUnit.class)
                .defineMethod("limitType", String.class, Visibility.PUBLIC).defaultValue("DEFAULT", String.class)
                .make()
                .load(dynamicCl, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Class<? extends Annotation> apiSignAnn = (Class<? extends Annotation>) byteBuddy
                .makeAnnotation()
                .name("io.github.atengk.boot.web.sign.annotation.ApiSign")
                .annotateType(runtimeRetention, methodAndTypeTarget)
                .defineMethod("verifyRequest", boolean.class, Visibility.PUBLIC).defaultValue(true, boolean.class)
                .defineMethod("signResponse", boolean.class, Visibility.PUBLIC).defaultValue(true, boolean.class)
                .defineMethod("expireSeconds", long.class, Visibility.PUBLIC).defaultValue(-1L, long.class)
                .defineMethod("algorithm", String.class, Visibility.PUBLIC).defaultValue("DEFAULT", String.class)
                .make()
                .load(dynamicCl, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Class<? extends Annotation> ipAccessAnn = (Class<? extends Annotation>) byteBuddy
                .makeAnnotation()
                .name("io.github.atengk.boot.web.ip.annotation.IpAccessControl")
                .annotateType(runtimeRetention, methodAndTypeTarget)
                .defineMethod("mode", String.class, Visibility.PUBLIC).defaultValue("WHITE_LIST", String.class)
                .defineMethod("rules", String[].class, Visibility.PUBLIC).defaultValue(new String[0], String[].class)
                .make()
                .load(dynamicCl, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Class<? extends Annotation> apiVersionAnn = (Class<? extends Annotation>) byteBuddy
                .makeAnnotation()
                .name("io.github.atengk.boot.web.version.annotation.ApiVersion")
                .annotateType(runtimeRetention, methodAndTypeTarget)
                .defineMethod("value", String.class, Visibility.PUBLIC).defaultValue("1", String.class)
                .defineMethod("number", int.class, Visibility.PUBLIC).defaultValue(0, int.class)
                .make()
                .load(dynamicCl, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Class<?> controllerType = byteBuddy
                .subclass(Object.class)
                .name("io.github.atengk.boot.openapi.customizer.DynamicAllGovernanceController")
                .defineMethod("execute", void.class, Visibility.PUBLIC)
                .intercept(net.bytebuddy.implementation.StubMethod.INSTANCE)
                .annotateMethod(
                        AnnotationDescription.Builder.ofType(SaCheckLogin.class).build(),
                        AnnotationDescription.Builder.ofType(SaCheckRole.class)
                                .defineArray("value", "admin").define("mode", SaMode.AND).build(),
                        AnnotationDescription.Builder.ofType(SaCheckPermission.class)
                                .defineArray("value", "order:pay").define("mode", SaMode.OR).build(),
                        AnnotationDescription.Builder.ofType(idempotentAnn)
                                .define("timeout", 5L).define("timeUnit", TimeUnit.SECONDS).build(),
                        AnnotationDescription.Builder.ofType(rateLimiterAnn)
                                .define("count", 20L).define("time", 10L)
                                .define("timeUnit", TimeUnit.SECONDS).define("limitType", "IP").build(),
                        AnnotationDescription.Builder.ofType(apiSignAnn)
                                .define("verifyRequest", true).define("signResponse", true)
                                .define("expireSeconds", 300L).define("algorithm", "HMAC_SHA256").build(),
                        AnnotationDescription.Builder.ofType(ipAccessAnn)
                                .define("mode", "WHITE_LIST")
                                .defineArray("rules", "10.0.0.0/8").build(),
                        AnnotationDescription.Builder.ofType(apiVersionAnn)
                                .define("value", "1").define("number", 2).build()
                )
                .make()
                .load(dynamicCl, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Object controller = controllerType.getDeclaredConstructor().newInstance();
        HandlerMethod handlerMethod = new HandlerMethod(controller, controllerType.getMethod("execute"));

        Operation operation = new Operation()
                .description("下单支付")
                .addParametersItem(new Parameter().in("header").name("X-Signature").description("手写商户签名"));

        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(dynamicCl);
            OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(new OpenApiProperties());
            customizer.customize(operation, handlerMethod);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }

        assertThat(operation.getDescription())
                .contains("**接口治理契约**：")
                .contains("需要登录认证 (`@SaCheckLogin`)")
                .contains("角色要求")
                .contains("权限要求")
                .contains("防重幂等")
                .contains("5 SECONDS")
                .contains("限流策略")
                .contains("10 SECONDS")
                .contains("20")
                .contains("接口加签")
                .contains("防重放窗口=`300s`")
                .contains("IP 访问控制")
                .contains("10.0.0.0/8")
                .contains("API 版本")
                .contains("`v2`");

        assertThat(operation.getParameters())
                .extracting(Parameter::getName)
                .containsExactlyInAnyOrder("X-Signature", "X-App-Id", "X-Timestamp", "X-Nonce");
        assertThat(operation.getParameters())
                .filteredOn(p -> "X-Signature".equals(p.getName()))
                .singleElement()
                .extracting(Parameter::getDescription)
                .isEqualTo("手写商户签名");
    }
}
