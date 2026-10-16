package io.github.atengk.boot.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
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

    @BeforeEach
    void setUp() {
        aspect = new WebLogAspect(new ObjectMapper());
        request = new MockHttpServletRequest("POST", "/api/test");
        request.setRemoteAddr("192.0.2.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    @AfterEach
    void tearDown() {
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
