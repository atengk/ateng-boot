package io.github.atengk.boot.web.aspect;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogUrlContext;
import io.github.atengk.boot.web.audit.OperateLogUrlCustomizer;
import io.github.atengk.boot.web.audit.OperateLogUrlStrategy;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化操作审计切面 OperateLogAspect 单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("结构化操作审计切面 OperateLogAspect 单元测试")
class OperateLogAspectTest {

    private final AtomicReference<OperateLogModel> capturedModel = new AtomicReference<>();
    private OperateLogService targetService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestUserVO {
        private String username;
        @FieldSensitive(SensitiveType.MOBILE_PHONE)
        private String phone;
        @FieldSensitive(SensitiveType.ID_CARD)
        private String idCard;
    }

    interface OperateLogService {
        Result<String> createUser(String username, String password);
        Result<TestUserVO> getUser(Long id);
        Result<Void> deleteUser(Long id);
        Result<String> noSaveMethod(String input);
        Result<String> loginWithoutExclude(String username, Map<String, Object> payload);
        Result<String> updateSecurity(Map<String, Object> payload);
        Result<String> submitDtoAndArray(AuthPayloadDTO dto, List<AuthPayloadDTO> items, Object[] extras);
        Result<String> circularArg(CircularTokenNode node);
        Result<Void> revokeSession(String sessionHandle);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class AuthPayloadDTO {
        private String account;
        private String accessToken;
        private String oldPassword;
    }

    static class CircularTokenNode {
        public String token = "secret-cycle-token";
        public CircularTokenNode self;
    }

    static class OperateLogServiceImpl implements OperateLogService {

        @Override
        @OperateLog(module = "用户管理", type = OperateTypeEnum.INSERT, description = "新增系统用户", excludeParams = {"password"})
        public Result<String> createUser(String username, String password) {
            return Result.success("created:" + username);
        }

        @Override
        @OperateLog(title = "用户中心", type = OperateTypeEnum.QUERY, description = "查询用户详情")
        public Result<TestUserVO> getUser(Long id) {
            return Result.success(new TestUserVO("ateng", "13800138000", "110101199003072345"));
        }

        @Override
        @OperateLog(module = "用户管理", type = OperateTypeEnum.DELETE, description = "删除系统用户")
        public Result<Void> deleteUser(Long id) {
            throw new BusinessException(403, "当前管理员无权删除根用户");
        }

        @Override
        @OperateLog(module = "性能模块", type = OperateTypeEnum.OTHER, saveParams = false, saveResult = false)
        public Result<String> noSaveMethod(String input) {
            return Result.success("echo:" + input);
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.OTHER, description = "默认基线脱敏")
        public Result<String> loginWithoutExclude(String username, Map<String, Object> payload) {
            return Result.success("ok:" + username);
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.UPDATE, description = "叠加排除字段", excludeParams = {"customPin"})
        public Result<String> updateSecurity(Map<String, Object> payload) {
            return Result.success("updated");
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.INSERT, description = "DTO与集合数组默认脱敏")
        public Result<String> submitDtoAndArray(AuthPayloadDTO dto, List<AuthPayloadDTO> items, Object[] extras) {
            return Result.success("dto-ok");
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.OTHER, description = "环引用安全降级")
        public Result<String> circularArg(CircularTokenNode node) {
            return Result.success("circular-ok");
        }

        @Override
        @OperateLog(
                module = "会话管理",
                type = OperateTypeEnum.DELETE,
                description = "注销在线会话",
                urlStrategy = OperateLogUrlStrategy.BEST_MATCHING_PATTERN
        )
        public Result<Void> revokeSession(String sessionHandle) {
            return Result.success();
        }
    }

    @BeforeEach
    void setUp() {
        capturedModel.set(null);
        OperateLogStorageProvider mockStorage = capturedModel::set;
        WebProperties properties = new WebProperties();
        OperateLogAspect aspect = new OperateLogAspect(mockStorage, null, JsonUtil.getObjectMapper(), null, properties);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new OperateLogServiceImpl());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(aspect);
        targetService = proxyFactory.getProxy();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.clear();
        TraceUtil.clear();
    }

    @Test
    @DisplayName("成功执行业务操作时，正确采集模块、操作动作、耗时、状态码 0 并对排除字段进行 [EXCLUDED] 掩码")
    void shouldCaptureSuccessfulOperationAndExcludeParams() {
        TraceUtil.setTraceId("trace-audit-001");
        UserContextHolder.set("operator-admin");
        TenantContextHolder.setTenantId("tenant-888");

        Result<String> result = targetService.createUser("ateng", "rawPassword123");

        assertThat(result.getCode()).isEqualTo(200);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getTraceId()).isEqualTo("trace-audit-001");
        assertThat(model.getTenantId()).isEqualTo("tenant-888");
        assertThat(model.getUserId()).isEqualTo("operator-admin");
        assertThat(model.getModule()).isEqualTo("用户管理");
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.INSERT);
        assertThat(model.getDescription()).isEqualTo("新增系统用户");
        assertThat(model.getStatus()).isEqualTo(0);
        assertThat(model.getErrorMsg()).isNull();
        assertThat(model.getCostTime()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(model.getOperateTime()).isNotNull();

        // 验证敏感入参排除掩码
        assertThat(model.getRequestParams()).contains("\"username\":\"ateng\"");
        assertThat(model.getRequestParams()).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(model.getRequestParams()).doesNotContain("rawPassword123");
    }

    @Test
    @DisplayName("出参结果序列化时自动联动 @FieldSensitive 执行视图脱敏，同时保障内存实体真明文不变量")
    void shouldDesensitizeResponseResultWhileKeepingPlaintextInMemory() {
        Result<TestUserVO> response = targetService.getUser(1001L);

        // 内存真明文断言（业务层实体对象绝不被污染为星号）
        assertThat(response.getData().getPhone()).isEqualTo("13800138000");
        assertThat(response.getData().getIdCard()).isEqualTo("110101199003072345");

        // 审计日志脱敏断言（出参输出被自动脱敏掩码）
        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getStatus()).isEqualTo(0);
        assertThat(model.getModule()).isEqualTo("用户中心");
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.QUERY);
        assertThat(model.getResponseResult()).contains("138****8000");
        assertThat(model.getResponseResult()).contains("110101********2345");
        assertThat(model.getResponseResult()).doesNotContain("13800138000");
        assertThat(model.getResponseResult()).doesNotContain("110101199003072345");
    }

    @Test
    @DisplayName("目标方法抛出异常时准确标记 status=1 并记录 errorMsg，同时 100% 原样重抛原始异常")
    void shouldCaptureExceptionMarkFailureAndRethrowIntact() {
        UserContextHolder.set("bad-actor");

        assertThatThrownBy(() -> targetService.deleteUser(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前管理员无权删除根用户")
                .extracting("code")
                .isEqualTo(403);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getStatus()).isEqualTo(1);
        assertThat(model.getModule()).isEqualTo("用户管理");
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.DELETE);
        assertThat(model.getErrorMsg()).contains("当前管理员无权删除根用户");
    }

    @Test
    @DisplayName("当 saveParams 与 saveResult 设为 false 时，不采集出入参内容")
    void shouldRespectSaveSwitches() {
        Result<String> result = targetService.noSaveMethod("secret-input");
        assertThat(result.getCode()).isEqualTo(200);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getRequestParams()).isEmpty();
        assertThat(model.getResponseResult()).isNull();
    }

    @Test
    @DisplayName("当全局配置 ateng.boot.web.operate-log.enabled=false 时，切面透传放行且不触发审计持久化")
    void shouldBypassWhenGloballyDisabled() {
        WebProperties properties = new WebProperties();
        properties.getOperateLog().setEnabled(false);

        OperateLogAspect aspect = new OperateLogAspect(capturedModel::set, null, JsonUtil.getObjectMapper(), null, properties);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new OperateLogServiceImpl());
        proxyFactory.addAspect(aspect);
        OperateLogService disabledService = proxyFactory.getProxy();

        Result<String> result = disabledService.createUser("test", "pwd");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(capturedModel.get()).isNull();
    }

    @Test
    @DisplayName("处于身份模拟态时，OperateLog 切面自动结构化记录操作人与被模拟人双维身份")
    void shouldCaptureOriginalUserIdAndRunAsFlagWhenImpersonating() {
        // 注册模拟态 SPI
        UserContextHolder.setUserProvider(new io.github.atengk.boot.core.spi.CurrentUserProvider() {
            @Override
            public String getCurrentUser() {
                return "target-user-66002";
            }

            @Override
            public String getOriginalUser() {
                return "admin-90001";
            }

            @Override
            public boolean isRunAs() {
                return true;
            }
        });

        Result<String> result = targetService.createUser("demo", "pwd");
        assertThat(result.getCode()).isEqualTo(200);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        // 业务被操作人
        assertThat(model.getUserId()).isEqualTo("target-user-66002");
        // 原始管理员
        assertThat(model.getOriginalUserId()).isEqualTo("admin-90001");
        // extra 扩展字典结构化记录
        assertThat(model.getExtra()).isNotNull();
        assertThat(model.getExtra().get("isRunAs")).isEqualTo(true);
        assertThat(model.getExtra().get("originalUserId")).isEqualTo("admin-90001");
        assertThat(model.getExtra().get("targetUserId")).isEqualTo("target-user-66002");
    }

    @Test
    @DisplayName("默认敏感参数脱敏基线：未配注解 excludeParams 时自动掩码全部默认敏感字段（忽略大小写及嵌套字段）")
    void shouldMaskDefaultSensitiveParamsWhenAnnotationExcludeEmpty() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("accessToken", "nested-access-token");
        nested.put("remark", "safe-note");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("password", "p1");
        payload.put("passwd", "p2");
        payload.put("pwd", "p3");
        payload.put("oldPassword", "p4");
        payload.put("newPassword", "p5");
        payload.put("confirmPassword", "p6");
        payload.put("secret", "s1");
        payload.put("token", "t1");
        payload.put("accessToken", "t2");
        payload.put("refreshToken", "t3");
        payload.put("AUTHORIZATION", "Bearer xyz");
        payload.put("nested", nested);

        targetService.loginWithoutExclude("alice", payload);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        String params = model.getRequestParams();
        assertThat(params).contains("\"username\":\"alice\"");
        assertThat(params).contains("\"remark\":\"safe-note\"");
        for (String expectedMaskedKey : List.of(
                "password", "passwd", "pwd", "oldPassword", "newPassword",
                "confirmPassword", "secret", "token", "accessToken", "refreshToken", "AUTHORIZATION"
        )) {
            assertThat(params).contains("\"" + expectedMaskedKey + "\":\"[EXCLUDED]\"");
        }
        assertThat(params).doesNotContain("nested-access-token");
        assertThat(params).doesNotContain("Bearer xyz");
    }

    @Test
    @DisplayName("全局 defaultExcludeParams 与注解 excludeParams 叠加生效，且支持自定义覆盖与 null 安全兜底")
    void shouldMergeAnnotationExcludesAndAllowCustomDefaultOverride() {
        Map<String, Object> mergedPayload = new LinkedHashMap<>();
        mergedPayload.put("password", "raw-pass");
        mergedPayload.put("customPin", "9527");
        mergedPayload.put("region", "cn-hz");

        targetService.updateSecurity(mergedPayload);
        String mergedParams = capturedModel.get().getRequestParams();
        assertThat(mergedParams).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(mergedParams).contains("\"customPin\":\"[EXCLUDED]\"");
        assertThat(mergedParams).contains("\"region\":\"cn-hz\"");

        // 自定义覆盖默认列表：仅屏蔽 apiKey（与注解 customPin 叠加），未纳入的 password 不再被默认基线屏蔽
        WebProperties customProps = new WebProperties();
        customProps.getOperateLog().setDefaultExcludeParams(List.of("apiKey"));
        OperateLogAspect customAspect = new OperateLogAspect(
                capturedModel::set, null, JsonUtil.getObjectMapper(), null, customProps);
        AspectJProxyFactory customProxy = new AspectJProxyFactory(new OperateLogServiceImpl());
        customProxy.setProxyTargetClass(true);
        customProxy.addAspect(customAspect);
        OperateLogService customService = customProxy.getProxy();

        Map<String, Object> overridePayload = new LinkedHashMap<>();
        overridePayload.put("apiKey", "ak-secret");
        overridePayload.put("customPin", "8848");
        overridePayload.put("password", "kept-for-override-check");
        customService.updateSecurity(overridePayload);

        String overrideParams = capturedModel.get().getRequestParams();
        assertThat(overrideParams).contains("\"apiKey\":\"[EXCLUDED]\"");
        assertThat(overrideParams).contains("\"customPin\":\"[EXCLUDED]\"");
        assertThat(overrideParams).contains("\"password\":\"kept-for-override-check\"");

        // null 防御不抛空指针
        customProps.getOperateLog().setDefaultExcludeParams(null);
        customService.loginWithoutExclude("bob", Map.of("plain", "ok"));
        assertThat(capturedModel.get().getRequestParams()).contains("\"plain\":\"ok\"");
    }

    @Test
    @DisplayName("DTO/集合/Object[] 数组与循环引用安全防御：默认基线穿透脱敏且环引用或异常回退绝不泄漏明文")
    void shouldMaskDtoCollectionArrayAndGuardCircularReferences() {
        AuthPayloadDTO dto = new AuthPayloadDTO("u1", "at-dto-secret", "old-pass-dto");
        AuthPayloadDTO itemDto = new AuthPayloadDTO("u2", "at-list-secret", "old-pass-list");
        Map<String, Object> extraMap = Map.of("refreshToken", "rt-array-secret", "tag", "ok");

        targetService.submitDtoAndArray(dto, List.of(itemDto), new Object[]{extraMap});

        String params = capturedModel.get().getRequestParams();
        assertThat(params).contains("\"account\":\"u1\"", "\"account\":\"u2\"", "\"tag\":\"ok\"");
        assertThat(params).doesNotContain("at-dto-secret", "old-pass-dto", "at-list-secret", "old-pass-list", "rt-array-secret");
        assertThat(params).contains("\"accessToken\":\"[EXCLUDED]\"", "\"oldPassword\":\"[EXCLUDED]\"", "\"refreshToken\":\"[EXCLUDED]\"");

        // 自引用对象环检测与异常回退：不抛 StackOverflowError，且绝不通过 raw toString() 泄露 token 明文
        CircularTokenNode cycle = new CircularTokenNode();
        cycle.self = cycle;
        targetService.circularArg(cycle);

        String cycleParams = capturedModel.get().getRequestParams();
        assertThat(cycleParams).isNotBlank();
        assertThat(cycleParams).doesNotContain("secret-cycle-token");
    }

    @Test
    @DisplayName("异步派发前同步采集并解析 User-Agent（含控制字符净化、截断、异步线程隔离与开关控制）")
    void shouldCaptureAndParseUserAgentSynchronouslyBeforeAsyncDispatch() throws Exception {
        String chromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", chromeUa + "\r\nEvilHeader: 1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // 1. 异步线程池中 RequestContextHolder 已销毁的场景仍可完整获取 UA 与解析结果
        CountDownLatch firstLatch = new CountDownLatch(1);
        CountDownLatch secondLatch = new CountDownLatch(2);
        AtomicReference<OperateLogModel> asyncCaptured = new AtomicReference<>();
        AtomicReference<Boolean> requestAttrsPresentInAsync = new AtomicReference<>(true);

        Executor asyncExecutor = task -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            requestAttrsPresentInAsync.set(RequestContextHolder.getRequestAttributes() != null);
            task.run();
            firstLatch.countDown();
            secondLatch.countDown();
        });
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("asyncExecutor", asyncExecutor);

        WebProperties props = new WebProperties();
        OperateLogAspect asyncAspect = new OperateLogAspect(
                asyncCaptured::set,
                beanFactory.getBeanProvider(Executor.class),
                JsonUtil.getObjectMapper(),
                null,
                props
        );
        AspectJProxyFactory asyncProxy = new AspectJProxyFactory(new OperateLogServiceImpl());
        asyncProxy.setProxyTargetClass(true);
        asyncProxy.addAspect(asyncAspect);
        OperateLogService asyncService = asyncProxy.getProxy();

        asyncService.createUser("ua-user", "secret");
        assertThat(firstLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(requestAttrsPresentInAsync.get()).isFalse();

        OperateLogModel asyncModel = asyncCaptured.get();
        assertThat(asyncModel).isNotNull();
        assertThat(asyncModel.getUserAgent()).doesNotContain("\r", "\n");
        assertThat(asyncModel.getUserAgent()).startsWith(chromeUa);
        assertThat(asyncModel.getBrowser()).isEqualTo("Chrome");
        assertThat(asyncModel.getOs()).isEqualTo("Windows");

        // 2. 最大长度截断与关闭 parseUserAgent
        props.getOperateLog().setUserAgentMaxLength(32);
        props.getOperateLog().setParseUserAgent(false);
        asyncService.noSaveMethod("truncate-check");
        assertThat(secondLatch.await(3, TimeUnit.SECONDS)).isTrue();
        OperateLogModel truncatedModel = asyncCaptured.get();
        assertThat(truncatedModel.getUserAgent()).hasSize(32);
        assertThat(truncatedModel.getBrowser()).isNull();
        assertThat(truncatedModel.getOs()).isNull();

        // 3. 关闭 collectUserAgent
        WebProperties disabledUaProps = new WebProperties();
        disabledUaProps.getOperateLog().setCollectUserAgent(false);
        OperateLogAspect disabledUaAspect = new OperateLogAspect(
                capturedModel::set, null, JsonUtil.getObjectMapper(), null, disabledUaProps);
        AspectJProxyFactory disabledUaProxy = new AspectJProxyFactory(new OperateLogServiceImpl());
        disabledUaProxy.setProxyTargetClass(true);
        disabledUaProxy.addAspect(disabledUaAspect);
        ((OperateLogService) disabledUaProxy.getProxy()).createUser("no-ua", "pwd");
        assertThat(capturedModel.get().getUserAgent()).isNull();
        assertThat(capturedModel.get().getBrowser()).isNull();
        assertThat(capturedModel.get().getOs()).isNull();

        // 4. 无 RequestAttributes / 无 UA 头平滑降级
        RequestContextHolder.resetRequestAttributes();
        targetService.createUser("non-web", "pwd");
        assertThat(capturedModel.get().getUserAgent()).isNull();
        assertThat(capturedModel.get().getBrowser()).isNull();
        assertThat(capturedModel.get().getOs()).isNull();
    }

    @Test
    @DisplayName("默认采集 RAW_URI；声明 BEST_MATCHING_PATTERN 时优先记录路由模板并在缺失时安全回退 RAW_URI")
    void shouldCaptureBestMatchingPatternAndFallbackToRawUri() {
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/api/users/raw-id");
        rawRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/users/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(rawRequest));

        targetService.createUser("alice", "pwd");
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/users/raw-id");

        MockHttpServletRequest patternRequest = new MockHttpServletRequest("DELETE", "/api/sessions/opaque-handle-96chars");
        patternRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/sessions/{sessionHandle}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(patternRequest));

        targetService.revokeSession("opaque-handle-96chars");
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/sessions/{sessionHandle}");

        MockHttpServletRequest fallbackRequest = new MockHttpServletRequest("DELETE", "/api/sessions/fallback-handle");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(fallbackRequest));

        targetService.revokeSession("fallback-handle");
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/sessions/fallback-handle");

        MockHttpServletRequest blankAttrRequest = new MockHttpServletRequest("DELETE", "/api/sessions/blank-handle");
        blankAttrRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "   ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(blankAttrRequest));

        targetService.revokeSession("blank-handle");
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/sessions/blank-handle");
    }

    @OperateLog(module = "类级会话模块", urlStrategy = OperateLogUrlStrategy.BEST_MATCHING_PATTERN)
    static class ClassLevelPatternService {

        @OperateLog(type = OperateTypeEnum.DELETE, description = "继承类级策略")
        public Result<Void> inheritClassStrategy() {
            return Result.success();
        }

        @OperateLog(type = OperateTypeEnum.QUERY, description = "方法显式回退 RAW_URI", urlStrategy = OperateLogUrlStrategy.RAW_URI)
        public Result<Void> overrideToRawUri() {
            return Result.success();
        }
    }

    @Test
    @DisplayName("四阶优先级裁决：方法显式策略 > 类级显式策略 > 全局 templateUrls 命中 > 全局 urlStrategy")
    void shouldResolveUrlStrategyWithFourTierPrecedence() {
        // 1. 类级 BEST_MATCHING_PATTERN 被未显式指定策略的方法继承；方法显式 RAW_URI 覆盖类级与全局
        WebProperties classProps = new WebProperties();
        classProps.getOperateLog().setUrlStrategy(OperateLogUrlStrategy.RAW_URI);
        classProps.getOperateLog().setTemplateUrls(List.of("/admin/**"));
        OperateLogAspect classAspect = new OperateLogAspect(capturedModel::set, null, JsonUtil.getObjectMapper(), null, classProps);
        AspectJProxyFactory classProxyFactory = new AspectJProxyFactory(new ClassLevelPatternService());
        classProxyFactory.setProxyTargetClass(true);
        classProxyFactory.addAspect(classAspect);
        ClassLevelPatternService classService = classProxyFactory.getProxy();

        MockHttpServletRequest req1 = new MockHttpServletRequest("DELETE", "/admin/sessions/secret-1");
        req1.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/sessions/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req1));

        classService.inheritClassStrategy();
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/admin/sessions/{id}");

        classService.overrideToRawUri();
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/admin/sessions/secret-1");

        // 2. 全局 templateUrls（支持 contextPath 剥离匹配）命中时切为路由模板，未命中保持 RAW_URI
        WebProperties ruleProps = new WebProperties();
        ruleProps.getOperateLog().setUrlStrategy(OperateLogUrlStrategy.RAW_URI);
        ruleProps.getOperateLog().setTemplateUrls(List.of("/admin/auth/online/**"));
        OperateLogAspect ruleAspect = new OperateLogAspect(capturedModel::set, null, JsonUtil.getObjectMapper(), null, ruleProps);
        AspectJProxyFactory ruleProxyFactory = new AspectJProxyFactory(new OperateLogServiceImpl());
        ruleProxyFactory.setProxyTargetClass(true);
        ruleProxyFactory.addAspect(ruleAspect);
        OperateLogService ruleService = ruleProxyFactory.getProxy();

        MockHttpServletRequest matchedReq = new MockHttpServletRequest("DELETE", "/api/admin/auth/online/aes96-token");
        matchedReq.setContextPath("/api");
        matchedReq.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/auth/online/{sessionId}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(matchedReq));

        ruleService.loginWithoutExclude("admin", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/admin/auth/online/{sessionId}");

        MockHttpServletRequest unmatchedReq = new MockHttpServletRequest("GET", "/api/system/users/42");
        unmatchedReq.setContextPath("/api");
        unmatchedReq.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/system/users/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(unmatchedReq));

        ruleService.loginWithoutExclude("admin", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/system/users/42");

        // 3. 全局 urlStrategy = BEST_MATCHING_PATTERN 生效；若误设为 DEFAULT 或 null 安全回退 RAW_URI
        ruleProps.getOperateLog().setTemplateUrls(List.of());
        ruleProps.getOperateLog().setUrlStrategy(OperateLogUrlStrategy.BEST_MATCHING_PATTERN);
        ruleService.loginWithoutExclude("admin", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/system/users/{id}");

        ruleProps.getOperateLog().setUrlStrategy(OperateLogUrlStrategy.DEFAULT);
        ruleService.loginWithoutExclude("admin", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/system/users/42");

        ruleProps.getOperateLog().setUrlStrategy(null);
        ruleService.loginWithoutExclude("admin", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/system/users/42");

        // 4. 请求 URI 为 null 且带 contextPath + templateUrls 时安全防御不抛 NPE
        ruleProps.getOperateLog().setTemplateUrls(List.of("/admin/auth/online/**"));
        MockHttpServletRequest nullUriReq = new MockHttpServletRequest();
        nullUriReq.setRequestURI(null);
        nullUriReq.setContextPath("/api");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(nullUriReq));

        ruleService.loginWithoutExclude("admin", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEmpty();
    }

    @Order(20)
    static class SecondOrderedUrlCustomizer implements OperateLogUrlCustomizer {
        @Override
        public String customize(OperateLogUrlContext context) {
            return context.resolvedUrl() + "|order20";
        }
    }

    @Order(10)
    static class FirstOrderedUrlCustomizer implements OperateLogUrlCustomizer {
        @Override
        public String customize(OperateLogUrlContext context) {
            return context.resolvedUrl() + "|order10";
        }
    }

    @Test
    @DisplayName("OperateLogUrlCustomizer 支持多定制器链式管道、Fail-Safe 异常/空值回退、控制字符净化与 PathPattern 兼容")
    void shouldExecuteUrlCustomizerPipelineSafelyAndSanitizeControlChars() {
        AtomicReference<OperateLogUrlContext> seenFirstCtx = new AtomicReference<>();
        AtomicReference<OperateLogUrlContext> seenLastCtx = new AtomicReference<>();

        OperateLogUrlCustomizer maskingCustomizer = ctx -> {
            seenFirstCtx.set(ctx);
            return ctx.resolvedUrl().replace("/secret-handle", "/***");
        };
        OperateLogUrlCustomizer throwingCustomizer = ctx -> {
            throw new IllegalStateException("customizer failed");
        };
        OperateLogUrlCustomizer nullReturningCustomizer = ctx -> null;
        OperateLogUrlCustomizer controlCharCustomizer = ctx -> {
            seenLastCtx.set(ctx);
            return ctx.resolvedUrl() + "\r\n\t\u007F/tail";
        };

        WebProperties props = new WebProperties();
        OperateLogAspect customizerAspect = new OperateLogAspect(
                capturedModel::set,
                null,
                JsonUtil.getObjectMapper(),
                null,
                props,
                List.of(maskingCustomizer, throwingCustomizer, nullReturningCustomizer, controlCharCustomizer)
        );
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new OperateLogServiceImpl());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(customizerAspect);
        OperateLogService service = proxyFactory.getProxy();

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/tokens/secret-handle");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                new PathPatternParser().parse("/api/tokens/{handle}"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        service.loginWithoutExclude("alice", Map.of("k", "v"));

        assertThat(seenFirstCtx.get()).isNotNull();
        assertThat(seenFirstCtx.get().effectiveStrategy()).isEqualTo(OperateLogUrlStrategy.RAW_URI);
        assertThat(seenFirstCtx.get().rawUri()).isEqualTo("/api/tokens/secret-handle");
        assertThat(seenFirstCtx.get().bestMatchingPattern()).isEqualTo("/api/tokens/{handle}");
        assertThat(seenFirstCtx.get().resolvedUrl()).isEqualTo("/api/tokens/secret-handle");
        assertThat(seenFirstCtx.get().method().getName()).isEqualTo("loginWithoutExclude");

        // throwing 与 null 返回后均保持上一级结果，最终结果被剥离控制字符
        assertThat(seenLastCtx.get().resolvedUrl()).isEqualTo("/api/tokens/***");
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/tokens/***/tail");

        // PathPattern 对象在 BEST_MATCHING_PATTERN 模式下同样可用；无请求上下文返回空串
        MockHttpServletRequest patternReq = new MockHttpServletRequest("DELETE", "/api/sessions/raw-96-token");
        patternReq.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                new PathPatternParser().parse("/api/sessions/{sessionHandle}"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(patternReq));

        targetService.revokeSession("raw-96-token");
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/sessions/{sessionHandle}");

        RequestContextHolder.resetRequestAttributes();
        targetService.revokeSession("no-request");
        assertThat(capturedModel.get().getRequestUrl()).isEmpty();

        // 构造器内部基于 @Order 自动排序（传入逆序 [20, 10] 仍按 10 -> 20 执行）
        OperateLogAspect orderedAspect = new OperateLogAspect(
                capturedModel::set,
                null,
                JsonUtil.getObjectMapper(),
                null,
                props,
                List.of(new SecondOrderedUrlCustomizer(), new FirstOrderedUrlCustomizer())
        );
        AspectJProxyFactory orderedProxy = new AspectJProxyFactory(new OperateLogServiceImpl());
        orderedProxy.setProxyTargetClass(true);
        orderedProxy.addAspect(orderedAspect);
        OperateLogService orderedService = orderedProxy.getProxy();

        MockHttpServletRequest orderedReq = new MockHttpServletRequest("GET", "/api/orders/100");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(orderedReq));
        orderedService.loginWithoutExclude("alice", Map.of("k", "v"));
        assertThat(capturedModel.get().getRequestUrl()).isEqualTo("/api/orders/100|order10|order20");
    }
}
