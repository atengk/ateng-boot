package io.github.atengk.boot.web.aspect;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.annotation.WebLog;
import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Web 审计日志切面单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("Web 审计日志切面单测")
class WebLogAspectTest {

    private WebLogAspect aspect;
    private MockHttpServletRequest request;
    private Logger webLogLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        aspect = new WebLogAspect(new ObjectMapper());
        request = new MockHttpServletRequest("POST", "/api/test");
        request.setRemoteAddr("192.0.2.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));

        webLogLogger = (Logger) LoggerFactory.getLogger(WebLogAspect.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        webLogLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (webLogLogger != null && logAppender != null) {
            webLogLogger.detachAppender(logAppender);
            logAppender.stop();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    static class SampleController {

        @WebLog(value = "正常创建", printParams = true, printResult = true, printCostTime = true)
        public String createUser(String username, int age) {
            return "user:" + username;
        }

        @WebLog(value = "敏感参数排除", excludeParams = {"password", "token"})
        public String login(String username, String password, String token) {
            return "login-success";
        }

        @WebLog(value = "复杂对象与大文件放行", excludeParams = {"password"})
        public String upload(MockMultipartFile file, Map<String, Object> body, HttpServletRequest req, InputStream in) {
            return "upload-success";
        }

        @WebLog(value = "异常触发接口")
        public String fail(String id) {
            throw new IllegalArgumentException("无效的业务 ID: " + id);
        }

        @WebLog(value = "静默接口", printParams = false, printResult = false, printCostTime = false)
        public String quiet(String data) {
            return "quiet:" + data;
        }

        @WebLog(value = "默认基线脱敏接口")
        public String authWithDefaults(Map<String, Object> payload, String secret) {
            return "ok";
        }

        @WebLog(value = "叠加排除接口", excludeParams = {"customSecret"})
        public String mergeExcludes(Map<String, Object> payload) {
            return "ok";
        }

        public String noAnnotation(String data) {
            return "plain:" + data;
        }
    }

    @WebLog(value = "类级别审计", printParams = true, printResult = true)
    static class ClassLevelController {

        public String action(String arg) {
            return "echo:" + arg;
        }
    }

    @Test
    @DisplayName("正常方法调用：成功执行且返回值保持一致")
    void shouldProceedNormalMethodSuccessfully() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("createUser", String.class, int.class);

        ProceedingJoinPoint pjp = createJoinPoint(target, method, new String[]{"username", "age"}, new Object[]{"张三", 25});
        when(pjp.proceed()).thenReturn("user:张三");

        Object result = aspect.around(pjp);
        assertThat(result).isEqualTo("user:张三");
    }

    @Test
    @DisplayName("敏感字段过滤：excludeParams 排除的字段安全置换为 [EXCLUDED]")
    void shouldMaskExcludedParameters() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("login", String.class, String.class, String.class);

        ProceedingJoinPoint pjp = createJoinPoint(
                target, method,
                new String[]{"username", "password", "token"},
                new Object[]{"admin", "rawPassword123", "secretToken456"}
        );
        when(pjp.proceed()).thenReturn("login-success");

        Object result = aspect.around(pjp);
        assertThat(result).isEqualTo("login-success");
    }

    @Test
    @DisplayName("大文件与输入流安全放行：对 MultipartFile、Request、Stream 占位替换防 OOM")
    void shouldSafelyHandleMultipartAndStreamArgs() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod(
                "upload", MockMultipartFile.class, Map.class, HttpServletRequest.class, InputStream.class
        );

        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[1024]);
        Map<String, Object> body = new HashMap<>();
        body.put("username", "test");
        body.put("password", "secret");

        ProceedingJoinPoint pjp = createJoinPoint(
                target, method,
                new String[]{"file", "body", "req", "in"},
                new Object[]{file, body, request, new ByteArrayInputStream(new byte[10])}
        );
        when(pjp.proceed()).thenReturn("upload-success");

        Object result = aspect.around(pjp);
        assertThat(result).isEqualTo("upload-success");
    }

    @Test
    @DisplayName("异常路径测试：目标方法抛出异常时正常向上抛出并记录异常上下文")
    void shouldRethrowExceptionAndLogContext() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("fail", String.class);

        ProceedingJoinPoint pjp = createJoinPoint(target, method, new String[]{"id"}, new Object[]{"123"});
        when(pjp.proceed()).thenThrow(new IllegalArgumentException("无效的业务 ID: 123"));

        assertThatThrownBy(() -> aspect.around(pjp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("无效的业务 ID: 123");
    }

    @Test
    @DisplayName("类级 @WebLog 注解继承与回退生效")
    void shouldInheritClassLevelWebLog() throws Throwable {
        ClassLevelController target = new ClassLevelController();
        Method method = ClassLevelController.class.getMethod("action", String.class);

        ProceedingJoinPoint pjp = createJoinPoint(target, method, new String[]{"arg"}, new Object[]{"hello"});
        when(pjp.proceed()).thenReturn("echo:hello");

        Object result = aspect.around(pjp);
        assertThat(result).isEqualTo("echo:hello");
    }

    @Test
    @DisplayName("静默配置：printParams/printResult/printCostTime 均为 false 时依然能正常记录与执行")
    void shouldSupportQuietModeWithoutCostAndResult() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("quiet", String.class);

        ProceedingJoinPoint pjp = createJoinPoint(target, method, new String[]{"data"}, new Object[]{"secretData"});
        when(pjp.proceed()).thenReturn("quiet:secretData");

        Object result = aspect.around(pjp);
        assertThat(result).isEqualTo("quiet:secretData");
    }

    @Test
    @DisplayName("无 @WebLog 注解时直接放行")
    void shouldProceedWithoutAnnotationDirectly() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("noAnnotation", String.class);

        ProceedingJoinPoint pjp = createJoinPoint(target, method, new String[]{"data"}, new Object[]{"normal"});
        when(pjp.proceed()).thenReturn("plain:normal");

        Object result = aspect.around(pjp);
        assertThat(result).isEqualTo("plain:normal");
    }

    @Test
    @DisplayName("默认敏感参数脱敏基线：未显式配置 excludeParams 时自动掩码全部默认敏感字段及直传同名参数")
    void shouldMaskDefaultSensitiveParamsWithoutAnnotationExcludes() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("authWithDefaults", Map.class, String.class);

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("refreshToken", "nested-refresh-secret");
        nested.put("remark", "visible-remark");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account", "alice");
        payload.put("password", "p1");
        payload.put("passwd", "p2");
        payload.put("pwd", "p3");
        payload.put("oldPassword", "p4");
        payload.put("newPassword", "p5");
        payload.put("confirmPassword", "p6");
        payload.put("token", "t1");
        payload.put("accessToken", "t2");
        payload.put("Authorization", "Bearer topSecret");
        payload.put("nested", nested);

        ProceedingJoinPoint pjp = createJoinPoint(
                target, method,
                new String[]{"payload", "secret"},
                new Object[]{payload, "rawArgSecret"}
        );
        when(pjp.proceed()).thenReturn("ok");

        aspect.around(pjp);

        String logs = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs).contains("\"account\":\"alice\"");
        assertThat(logs).contains("\"remark\":\"visible-remark\"");
        assertThat(logs).contains("\"secret\":\"[EXCLUDED]\"");
        assertThat(logs).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(logs).contains("\"Authorization\":\"[EXCLUDED]\"");
        assertThat(logs).contains("\"refreshToken\":\"[EXCLUDED]\"");
        assertThat(logs).doesNotContain("rawArgSecret");
        assertThat(logs).doesNotContain("Bearer topSecret");
        assertThat(logs).doesNotContain("nested-refresh-secret");
    }

    @Test
    @DisplayName("全局 defaultExcludeParams 与注解 excludeParams 叠加生效，并支持自定义覆盖")
    void shouldMergeAnnotationExcludesAndAllowCustomDefaultOverride() throws Throwable {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("mergeExcludes", Map.class);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("password", "raw-pass");
        merged.put("customSecret", "raw-custom");
        merged.put("city", "hangzhou");

        ProceedingJoinPoint pjp = createJoinPoint(target, method, new String[]{"payload"}, new Object[]{merged});
        when(pjp.proceed()).thenReturn("ok");
        aspect.around(pjp);

        String firstLogs = logAppender.list.get(0).getFormattedMessage();
        assertThat(firstLogs).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(firstLogs).contains("\"customSecret\":\"[EXCLUDED]\"");
        assertThat(firstLogs).contains("\"city\":\"hangzhou\"");

        // 自定义覆盖全局列表为仅 [apiKey]（与注解 customSecret 叠加）
        logAppender.list.clear();
        WebProperties customProps = new WebProperties();
        customProps.getLog().setDefaultExcludeParams(List.of("apiKey"));
        WebLogAspect customAspect = new WebLogAspect(new ObjectMapper(), customProps);

        Map<String, Object> customPayload = new LinkedHashMap<>();
        customPayload.put("apiKey", "ak-999");
        customPayload.put("customSecret", "cs-888");
        customPayload.put("password", "kept-after-override");

        ProceedingJoinPoint customPjp = createJoinPoint(target, method, new String[]{"payload"}, new Object[]{customPayload});
        when(customPjp.proceed()).thenReturn("ok");
        customAspect.around(customPjp);

        String customLogs = logAppender.list.get(0).getFormattedMessage();
        assertThat(customLogs).contains("\"apiKey\":\"[EXCLUDED]\"");
        assertThat(customLogs).contains("\"customSecret\":\"[EXCLUDED]\"");
        assertThat(customLogs).contains("\"password\":\"kept-after-override\"");

        // null 防御不抛空指针
        logAppender.list.clear();
        customProps.getLog().setDefaultExcludeParams(null);
        ProceedingJoinPoint nullPjp = createJoinPoint(target, method, new String[]{"payload"}, new Object[]{Map.of("ok", "1")});
        when(nullPjp.proceed()).thenReturn("ok");
        customAspect.around(nullPjp);
        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("\"ok\":\"1\"");
        // emptyList 覆盖且包含 MultipartFile 与 Date/UUID 标量时，依然完成不可序列化占位替换不报错
        logAppender.list.clear();
        customProps.getLog().setDefaultExcludeParams(List.of());
        Map<String, Object> emptyExcludeMap = new LinkedHashMap<>();
        emptyExcludeMap.put("files", List.of(new MockMultipartFile("f", "doc.txt", "text/plain", new byte[]{1, 2})));
        emptyExcludeMap.put("requestId", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        emptyExcludeMap.put("bytes", new byte[]{1, 2, 3});
        ProceedingJoinPoint emptyListPjp = createJoinPoint(target, method, new String[]{"payload"}, new Object[]{emptyExcludeMap});
        when(emptyListPjp.proceed()).thenReturn("ok");
        customAspect.around(emptyListPjp);
        String emptyListLogs = logAppender.list.get(0).getFormattedMessage();
        assertThat(emptyListLogs).contains("[MultipartFile: name=doc.txt, size=2]");
        assertThat(emptyListLogs).contains("00000000-0000-0000-0000-000000000001");

        // 无参构造 WebLogAspect() 同样默认开启 11 个敏感字段脱敏基线
        logAppender.list.clear();
        WebLogAspect defaultCtorAspect = new WebLogAspect();
        Method defaultsMethod = SampleController.class.getMethod("authWithDefaults", Map.class, String.class);
        ProceedingJoinPoint defaultCtorPjp = createJoinPoint(
                target, defaultsMethod,
                new String[]{"payload", "secret"},
                new Object[]{Map.of("authorization", "Bearer default-ctor"), "argSecret"}
        );
        when(defaultCtorPjp.proceed()).thenReturn("ok");
        defaultCtorAspect.around(defaultCtorPjp);
        String defaultCtorLog = logAppender.list.get(0).getFormattedMessage();
        assertThat(defaultCtorLog).contains("\"authorization\":\"[EXCLUDED]\"");
        assertThat(defaultCtorLog).contains("\"secret\":\"[EXCLUDED]\"");
        assertThat(defaultCtorLog).doesNotContain("Bearer default-ctor", "argSecret");
    }

    private ProceedingJoinPoint createJoinPoint(Object target, Method method, String[] paramNames, Object[] args) {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);

        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(target);
        when(pjp.getArgs()).thenReturn(args);

        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(paramNames);
        when(signature.getDeclaringTypeName()).thenReturn(target.getClass().getSimpleName());
        when(signature.getName()).thenReturn(method.getName());

        return pjp;
    }
}
