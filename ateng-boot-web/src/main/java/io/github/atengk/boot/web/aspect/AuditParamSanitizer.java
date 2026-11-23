package io.github.atengk.boot.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.JsonUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 审计日志切面入参提取与敏感字段脱敏共享原语
 * 统一合并全局默认排除基线与注解级排除清单，对方法入参及嵌套对象/集合执行忽略大小写的递归掩码与环引用防御
 *
 * @author Ateng
 * @since 2026-11-14
 */
final class AuditParamSanitizer {

    private static final String EXCLUDED_MASK = "[EXCLUDED]";
    private static final String CIRCULAR_REF_MASK = "[CIRCULAR_REF]";

    private AuditParamSanitizer() {
    }

    /**
     * 提取连接点入参并结合全局默认基线与注解级 excludeParams 执行递归脱敏
     *
     * @param joinPoint          切入连接点
     * @param defaultExcludes    全局默认敏感参数名清单
     * @param annotationExcludes 注解显式指定的敏感参数名数组
     * @param objectMapper       Jackson 序列化器
     * @return 脱敏后的参数名值映射
     */
    static Map<String, Object> extractParams(ProceedingJoinPoint joinPoint,
                                             Collection<String> defaultExcludes,
                                             String[] annotationExcludes,
                                             ObjectMapper objectMapper) {
        if (joinPoint == null || !(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return Collections.emptyMap();
        }
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }

        ObjectMapper mapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
        Set<String> excludedSet = mergeExcludeParams(defaultExcludes, annotationExcludes);
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        Map<String, Object> paramMap = new LinkedHashMap<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String rawName = (paramNames != null && i < paramNames.length && StringUtils.hasText(paramNames[i]))
                    ? paramNames[i].trim()
                    : "arg" + i;
            if (excludedSet.contains(rawName.toLowerCase(Locale.ROOT))) {
                paramMap.put(rawName, EXCLUDED_MASK);
                continue;
            }
            paramMap.put(rawName, sanitizeArg(args[i], excludedSet, mapper, visited));
        }
        return paramMap;
    }

    /**
     * 合并全局默认排除基线与注解显式排除参数列表（统一去除首尾空白并转小写）
     *
     * @param defaultExcludes    全局默认排除列表
     * @param annotationExcludes 注解级排除数组
     * @return 归一化后的排除键集合
     */
    static Set<String> mergeExcludeParams(Collection<String> defaultExcludes, String[] annotationExcludes) {
        Set<String> excludedSet = new HashSet<>();
        addNormalizedNames(excludedSet, defaultExcludes);
        if (annotationExcludes != null) {
            for (String item : annotationExcludes) {
                addNormalizedName(excludedSet, item);
            }
        }
        return excludedSet;
    }

    /**
     * 对方法返回值执行大对象/流占位替换并安全序列化为 JSON 文本
     *
     * @param result       目标方法返回值
     * @param objectMapper Jackson 序列化器
     * @return 安全脱敏或占位后的展示文本
     */
    static String sanitizeResult(Object result, ObjectMapper objectMapper) {
        if (result == null) {
            return "null";
        }
        String placeholder = getSkipPlaceholder(result);
        if (placeholder != null) {
            return placeholder;
        }
        return toJson(result, objectMapper);
    }

    /**
     * 将对象安全序列化为 JSON，失败时不透传原始对象 toString 以防敏感字段明文泄漏
     *
     * @param obj          待序列化对象
     * @param objectMapper Jackson 序列化器
     * @return JSON 文本或安全错误摘要占位符
     */
    static String toJson(Object obj, ObjectMapper objectMapper) {
        if (obj == null) {
            return "null";
        }
        ObjectMapper mapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[JSON_SERIALIZE_ERROR:" + obj.getClass().getSimpleName() + "]";
        }
    }

    private static void addNormalizedNames(Set<String> target, Collection<String> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (String item : source) {
            addNormalizedName(target, item);
        }
    }

    private static void addNormalizedName(Set<String> target, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            target.add(candidate.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static Object sanitizeArg(Object arg,
                                      Set<String> excludedSet,
                                      ObjectMapper objectMapper,
                                      Set<Object> visited) {
        if (arg == null) {
            return null;
        }
        String placeholder = getSkipPlaceholder(arg);
        if (placeholder != null) {
            return placeholder;
        }
        if (isPrimitiveOrBasic(arg)) {
            return arg;
        }
        if (!visited.add(arg)) {
            return CIRCULAR_REF_MASK;
        }
        try {
            if (arg instanceof Map<?, ?> map) {
                return filterMap(map, excludedSet, objectMapper, visited);
            }
            if (arg instanceof Collection<?> col) {
                return sanitizeElements(col, col.size(), excludedSet, objectMapper, visited);
            }
            if (arg instanceof Object[] array) {
                return sanitizeElements(Arrays.asList(array), array.length, excludedSet, objectMapper, visited);
            }
            if (excludedSet.isEmpty()) {
                return arg;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.convertValue(arg, Map.class);
                if (map == null) {
                    return null;
                }
                return filterMap(map, excludedSet, objectMapper, visited);
            } catch (Exception e) {
                // 转换失败时严禁原样吐出原始 DTO，避免序列化或 toString() 泄露明文凭据
                return "[UNSERIALIZABLE:" + arg.getClass().getSimpleName() + "]";
            }
        } finally {
            visited.remove(arg);
        }
    }

    private static List<Object> sanitizeElements(Iterable<?> elements,
                                                 int initialCapacity,
                                                 Set<String> excludedSet,
                                                 ObjectMapper objectMapper,
                                                 Set<Object> visited) {
        List<Object> list = new ArrayList<>(initialCapacity);
        for (Object item : elements) {
            list.add(sanitizeArg(item, excludedSet, objectMapper, visited));
        }
        return list;
    }

    private static Map<Object, Object> filterMap(Map<?, ?> map,
                                                 Set<String> excludedSet,
                                                 ObjectMapper objectMapper,
                                                 Set<Object> visited) {
        if (map == null) {
            return Collections.emptyMap();
        }
        Map<Object, Object> filtered = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (key != null && excludedSet.contains(key.toString().trim().toLowerCase(Locale.ROOT))) {
                filtered.put(key, EXCLUDED_MASK);
            } else {
                filtered.put(key, sanitizeArg(entry.getValue(), excludedSet, objectMapper, visited));
            }
        }
        return filtered;
    }

    private static String getSkipPlaceholder(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof MultipartFile file) {
            return "[MultipartFile: name=" + file.getOriginalFilename() + ", size=" + file.getSize() + "]";
        }
        if (arg instanceof MultipartFile[] files) {
            return "[MultipartFile[], length=" + files.length + "]";
        }
        if (arg instanceof ServletRequest) {
            return "[HttpServletRequest]";
        }
        if (arg instanceof ServletResponse) {
            return "[HttpServletResponse]";
        }
        if (arg instanceof InputStream || arg instanceof OutputStream) {
            return "[Stream]";
        }
        if (arg instanceof Reader || arg instanceof Writer) {
            return "[Reader/Writer]";
        }
        if (arg instanceof BindingResult) {
            return "[BindingResult]";
        }
        return null;
    }

    private static boolean isPrimitiveOrBasic(Object arg) {
        Class<?> clazz = arg.getClass();
        return arg instanceof String
                || arg instanceof Number
                || arg instanceof Boolean
                || arg instanceof Character
                || arg instanceof TemporalAccessor
                || arg instanceof Date
                || arg instanceof UUID
                || arg instanceof URI
                || arg instanceof URL
                || arg instanceof Locale
                || clazz.isPrimitive()
                || clazz.isEnum()
                || (clazz.isArray() && clazz.getComponentType().isPrimitive());
    }
}
