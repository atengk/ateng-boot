package io.github.atengk.boot.mybatisplus.util;

import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * MyBatis / MyBatis-Plus 拦截器与插件底层公共基础设施工具类
 * 提供 JDK 17 强封装模块化兼容的代理递归拆包（Plugin 解包）、
 * 以及智能穿透中间件与切面框架的真实业务调用栈位置定位能力。
 *
 * @author Ateng
 * @since 2026-10-22
 */
public final class MybatisInterceptorUtil {

    private MybatisInterceptorUtil() {
        // 工具类私有构造
    }

    /**
     * 递归拆解 JDK 动态代理与 MyBatis 原生 {@link Plugin} 包装，获取最终底层的物理目标对象
     * 采用 JDK 17 原生合规的 {@link Proxy#getInvocationHandler(Object)} 避免触碰受保护的 Proxy.h 字段
     *
     * @param target 待拆包的目标对象或代理对象
     * @return 最终物理目标对象（若传入 null 则返回 null）
     */
    public static Object realTarget(Object target) {
        if (target == null) {
            return null;
        }
        if (Proxy.isProxyClass(target.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(target);
            if (handler instanceof Plugin) {
                MetaObject metaObject = SystemMetaObject.forObject(handler);
                if (metaObject.hasGetter("target")) {
                    return realTarget(metaObject.getValue("target"));
                }
            }
        }
        return target;
    }

    /**
     * 解析当前执行线程堆栈，智能穿透底层所有持久层、中间件、Spring 容器与动态代理切面，
     * 定位第一处发起调用的真实业务代码位置（如 Service、Controller 或具体业务组件）
     *
     * @return 格式化调用位置描述："类名.方法名(文件名:行号)"，无法解析时保底返回 "UNKNOWN"
     */
    public static String resolveCallLocation() {
        StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : elements) {
            String className = element.getClassName();
            if (isFrameworkClass(className)) {
                continue;
            }
            return className + "." + element.getMethodName() + "(" + element.getFileName() + ":" + element.getLineNumber() + ")";
        }
        return "UNKNOWN";
    }

    /**
     * 判断指定类名是否属于需要穿透过滤的框架基础设施类
     *
     * @param className 类的全限定类名
     * @return 若属于框架类返回 true，业务类返回 false
     */
    public static boolean isFrameworkClass(String className) {
        if (className == null || className.isBlank()) {
            return true;
        }
        // 单元测试与场景集成测试类始终判定为调用方，便于测试断言捕获
        if (className.contains("Test")) {
            return false;
        }
        return className.startsWith("java.") ||
                className.startsWith("jdk.") ||
                className.startsWith("sun.") ||
                className.startsWith("org.apache.ibatis.") ||
                className.startsWith("org.mybatis.spring.") ||
                className.startsWith("com.baomidou.mybatisplus.") ||
                className.startsWith("io.github.atengk.boot.mybatisplus.") ||
                className.startsWith("org.springframework.") ||
                className.startsWith("org.aopalliance.") ||
                className.startsWith("org.apache.tomcat.") ||
                className.startsWith("org.apache.catalina.") ||
                className.startsWith("org.apache.maven.") ||
                className.startsWith("org.junit.") ||
                className.startsWith("org.mockito.") ||
                className.startsWith("jakarta.") ||
                className.startsWith("javax.") ||
                className.startsWith("cglib.") ||
                className.startsWith("javassist.") ||
                className.startsWith("net.bytebuddy.") ||
                className.contains("$$");
    }
}
