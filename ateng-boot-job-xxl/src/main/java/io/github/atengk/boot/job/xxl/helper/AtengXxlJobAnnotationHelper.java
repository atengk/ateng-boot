package io.github.atengk.boot.job.xxl.helper;

import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * {@link AtengXxlJob} 与 {@link XxlJob} 注解元数据共享解析器
 *
 * <p>统一执行器扫描注册、脱网调试器探测与切面路由之间的 Handler 命名规范及生命周期方法解析，
 * 保证不同接入入口的命名优先级、空白修剪与代理拆包行为 100% 一致。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class AtengXxlJobAnnotationHelper {

    private static final Logger log = LoggerFactory.getLogger(AtengXxlJobAnnotationHelper.class);

    private AtengXxlJobAnnotationHelper() {
    }

    /**
     * 解析任务全局唯一 Handler 名称
     *
     * <p>遵循注解契约：若方法同时标注了有效的 {@link XxlJob#value()}，则优先以 {@code @XxlJob.value()} 为准；
     * 其次取 {@link AtengXxlJob#value()}；若两者均未显式指定则回退至目标方法名。</p>
     *
     * @param method      目标调度方法
     * @param atengXxlJob {@link AtengXxlJob} 注解实例（可为 null）
     * @return 修剪空白后的规范化 Handler 名称
     */
    public static String resolveHandlerName(Method method, AtengXxlJob atengXxlJob) {
        Objects.requireNonNull(method, "method 不能为空");
        XxlJob xxlJob = AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
        if (xxlJob != null && StringUtils.hasText(xxlJob.value())) {
            return xxlJob.value().trim();
        }
        if (atengXxlJob != null && StringUtils.hasText(atengXxlJob.value())) {
            return atengXxlJob.value().trim();
        }
        return method.getName();
    }

    /**
     * 判断当前标注了 {@link AtengXxlJob} 的方法是否仍需作为独立或补充 Handler 执行扫描注册
     *
     * @param method      目标调度方法
     * @param atengXxlJob {@link AtengXxlJob} 注解实例
     * @return true 若未与已有有效 {@link XxlJob#value()} 重叠，需补充注册
     */
    public static boolean shouldRegisterAtengHandler(Method method, AtengXxlJob atengXxlJob) {
        if (method == null || atengXxlJob == null) {
            return false;
        }
        XxlJob xxlJob = AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
        if (xxlJob != null && StringUtils.hasText(xxlJob.value())) {
            return false;
        }
        return true;
    }

    /**
     * 解析并授权访问任务生命周期方法（init / destroy）
     *
     * @param bean          目标 Spring Bean（可能为 AOP 代理）
     * @param methodName    声明的生命周期方法名
     * @param executeMethod 调度主方法
     * @param phase         生命周期阶段标识（init / destroy）
     * @param failOnMissing 找不到方法时是否快速失败抛出异常
     * @return 解析到的方法反射对象；未配置或非严格模式下未找到时返回 null
     */
    public static Method resolveLifecycleMethod(
            Object bean,
            String methodName,
            Method executeMethod,
            String phase,
            boolean failOnMissing) {
        if (!StringUtils.hasText(methodName) || bean == null) {
            return null;
        }
        String trimmed = methodName.trim();
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Method found = ReflectionUtils.findMethod(bean.getClass(), trimmed);
        if (found == null) {
            found = ReflectionUtils.findMethod(targetClass, trimmed);
        }
        if (found != null) {
            ReflectionUtils.makeAccessible(found);
            return found;
        }
        if (failOnMissing) {
            String executeMethodName = executeMethod != null ? executeMethod.getName() : "<unknown>";
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "xxl-job method-jobhandler " + phase + "Method invalid, for["
                            + targetClass.getName() + "#" + executeMethodName + "].");
        }
        log.debug("目标类 [{}] 中未找到声明的生命周期方法 [{}]", targetClass.getName(), methodName);
        return null;
    }
}
