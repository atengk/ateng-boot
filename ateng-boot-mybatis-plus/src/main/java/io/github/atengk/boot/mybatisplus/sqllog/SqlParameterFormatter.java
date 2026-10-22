package io.github.atengk.boot.mybatisplus.sqllog;

import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Field;
import java.sql.Blob;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自研纯原生字符流参数安全反填引擎与敏感脱敏引擎
 * 采用零依赖 Token 状态机流式逐字符扫描，智能识别并跳过单引号/双引号/反引号常量及 SQL 注释内部的问号占位符，
 * 严格遵循 JDBC 类型安全反填字面量，杜绝 String.replaceFirst 正则替换异常；
 * 内置对 @FieldEncrypt 实体加密属性与密码凭据关键字的探测与自适应掩码脱敏。
 *
 * @author Ateng
 * @since 2026-10-22
 */
public final class SqlParameterFormatter {

    /**
     * 敏感字段默认掩码标识
     */
    public static final String SENSITIVE_MASK = "******";

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 实体类字段 @FieldEncrypt 注解探测并发缓存，消除高频反射损耗
     */
    private static final Map<String, Boolean> FIELD_ENCRYPT_CACHE = new ConcurrentHashMap<>();

    private SqlParameterFormatter() {
        // 工具类私有构造
    }

    /**
     * 将 JDBC 参数对象格式化为符合 ANSI SQL 语法的字面量字符串
     *
     * @param value 原始参数对象
     * @return ANSI SQL 字面量字符串
     */
    public static String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return "'" + escapeSql(value.toString()) + "'";
        }
        if (value instanceof LocalDateTime ldt) {
            return "'" + DATETIME_FORMATTER.format(ldt) + "'";
        }
        if (value instanceof LocalDate ld) {
            return "'" + DATE_FORMATTER.format(ld) + "'";
        }
        if (value instanceof LocalTime lt) {
            return "'" + TIME_FORMATTER.format(lt) + "'";
        }
        if (value instanceof Instant instant) {
            return "'" + DATETIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault())) + "'";
        }
        if (value instanceof TemporalAccessor ta) {
            return "'" + DATETIME_FORMATTER.format(ta) + "'";
        }
        if (value instanceof java.sql.Date sqlDate) {
            return "'" + DATE_FORMATTER.format(sqlDate.toLocalDate()) + "'";
        }
        if (value instanceof java.sql.Time sqlTime) {
            return "'" + TIME_FORMATTER.format(sqlTime.toLocalTime()) + "'";
        }
        if (value instanceof java.sql.Timestamp sqlTimestamp) {
            return "'" + DATETIME_FORMATTER.format(sqlTimestamp.toLocalDateTime()) + "'";
        }
        if (value instanceof Date utilDate) {
            return "'" + DATETIME_FORMATTER.format(Instant.ofEpochMilli(utilDate.getTime()).atZone(ZoneId.systemDefault())) + "'";
        }
        if (value instanceof byte[] || value instanceof Byte[] || value instanceof Blob) {
            return "'<BLOB>'";
        }
        if (value instanceof Enum<?> e) {
            return "'" + e.name() + "'";
        }
        return "'" + escapeSql(value.toString()) + "'";
    }

    /**
     * 将原始 SQL 与参数列表按字符流状态机安全拼装为可执行带参 SQL（纯原生流式扫描，杜绝 replaceFirst 正则异常）
     * 智能识别并跳过单引号/双引号/反引号常量与 SQL 注释中的 ? 字符
     *
     * @param rawSql 包含 ? 占位符的原始 SQL
     * @param parameters 参数列表（已脱敏或原始对象）
     * @return 完整带参可执行 SQL
     */
    public static String formatSql(String rawSql, List<?> parameters) {
        if (rawSql == null || rawSql.isBlank()) {
            return rawSql;
        }
        if (parameters == null || parameters.isEmpty()) {
            return rawSql;
        }

        StringBuilder sb = new StringBuilder(rawSql.length() + parameters.size() * 16);
        int paramIndex = 0;
        int paramCount = parameters.size();
        int len = rawSql.length();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < len; i++) {
            char c = rawSql.charAt(i);
            char next = (i + 1 < len) ? rawSql.charAt(i + 1) : '\0';

            if (inLineComment) {
                sb.append(c);
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }

            if (inBlockComment) {
                sb.append(c);
                if (c == '*' && next == '/') {
                    sb.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            if (inSingleQuote) {
                sb.append(c);
                if (c == '\'') {
                    if (next == '\'') {
                        // 标准 SQL 单引号转义: ''
                        sb.append(next);
                        i++;
                    } else {
                        // 校验是否由奇数个反斜杠转义
                        int backslashCount = countPrecedingBackslashes(rawSql, i);
                        if ((backslashCount % 2) == 0) {
                            inSingleQuote = false;
                        }
                    }
                }
                continue;
            }

            if (inDoubleQuote) {
                sb.append(c);
                if (c == '"') {
                    if (next == '"') {
                        sb.append(next);
                        i++;
                    } else {
                        int backslashCount = countPrecedingBackslashes(rawSql, i);
                        if ((backslashCount % 2) == 0) {
                            inDoubleQuote = false;
                        }
                    }
                }
                continue;
            }

            if (inBacktick) {
                sb.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }

            // Normal 状态判断
            if (c == '-' && next == '-') {
                sb.append(c).append(next);
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                sb.append(c).append(next);
                i++;
                inBlockComment = true;
                continue;
            }
            if (c == '\'') {
                sb.append(c);
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                sb.append(c);
                inDoubleQuote = true;
                continue;
            }
            if (c == '`') {
                sb.append(c);
                inBacktick = true;
                continue;
            }

            if (c == '?') {
                if (paramIndex < paramCount) {
                    Object param = parameters.get(paramIndex++);
                    sb.append(formatValue(param));
                } else {
                    sb.append('?');
                }
                continue;
            }

            sb.append(c);
        }

        return sb.toString();
    }

    /**
     * 从 MyBatis BoundSql 提取参数、应用敏感字段脱敏掩码，并反填生成完整可执行带参 SQL
     *
     * @param boundSql MyBatis BoundSql
     * @param configuration MyBatis Configuration（可为 null）
     * @param maskSensitive 是否开启敏感数据脱敏掩码
     * @return 完整带参可执行 SQL
     */
    public static String formatSql(BoundSql boundSql, Configuration configuration, boolean maskSensitive) {
        if (boundSql == null) {
            return "";
        }
        List<Object> parameters = extractParameters(boundSql, configuration, maskSensitive);
        return formatSql(boundSql.getSql(), parameters);
    }

    /**
     * 从 MyBatis BoundSql 提取参数并反填生成带参 SQL，并根据 formatSql 开关决定是否美化
     *
     * @param boundSql MyBatis BoundSql
     * @param configuration MyBatis Configuration（可为 null）
     * @param maskSensitive 是否开启敏感数据脱敏掩码
     * @param formatSql 是否进行多行缩进美化
     * @return 最终 SQL
     */
    public static String formatSql(BoundSql boundSql, Configuration configuration, boolean maskSensitive, boolean formatSql) {
        String sql = formatSql(boundSql, configuration, maskSensitive);
        return formatSql ? prettify(sql) : compress(sql);
    }

    /**
     * 从 MyBatis BoundSql 提取参数列表（支持嵌套属性、动态 SQL additionalParameters 及敏感字段脱敏）
     *
     * @param boundSql MyBatis BoundSql
     * @param configuration MyBatis Configuration（可为 null）
     * @param maskSensitive 是否开启敏感数据脱敏掩码
     * @return 提取后的参数列表
     */
    public static List<Object> extractParameters(BoundSql boundSql, Configuration configuration, boolean maskSensitive) {
        if (boundSql == null) {
            return Collections.emptyList();
        }
        Object parameterObject = boundSql.getParameterObject();
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        if (parameterMappings == null || parameterMappings.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> result = new ArrayList<>(parameterMappings.size());
        MetaObject metaParam = null;
        if (parameterObject != null) {
            metaParam = (configuration != null)
                    ? configuration.newMetaObject(parameterObject)
                    : SystemMetaObject.forObject(parameterObject);
        }

        for (ParameterMapping mapping : parameterMappings) {
            String propName = mapping.getProperty();
            Object value = null;
            if (boundSql.hasAdditionalParameter(propName)) {
                value = boundSql.getAdditionalParameter(propName);
            } else if (parameterObject != null) {
                if (configuration != null && configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else if (metaParam != null && metaParam.hasGetter(propName)) {
                    value = metaParam.getValue(propName);
                } else if (parameterMappings.size() == 1 && configuration != null) {
                    value = parameterObject;
                }
            }

            if (maskSensitive && isSensitive(mapping, parameterObject, propName)) {
                value = SENSITIVE_MASK;
            }
            result.add(value);
        }
        return result;
    }

    /**
     * 判断指定属性或映射是否为敏感字段（支持 @FieldEncrypt 探测、EncryptTypeHandler 及关键词匹配）
     *
     * @param mapping 参数映射（可为 null）
     * @param parameterObject 参数根对象（可为 null）
     * @param propName 属性路径名（可为 null）
     * @return 是否为敏感属性
     */
    public static boolean isSensitive(ParameterMapping mapping, Object parameterObject, String propName) {
        // 1. 若映射声明了 EncryptTypeHandler，直接判定为敏感
        if (mapping != null && mapping.getTypeHandler() != null) {
            if (mapping.getTypeHandler() instanceof EncryptTypeHandler ||
                    mapping.getTypeHandler().getClass().getName().contains("EncryptTypeHandler")) {
                return true;
            }
        }

        // 2. 检查实体字段是否标注 @FieldEncrypt
        if (isFieldEncrypted(parameterObject, propName)) {
            return true;
        }

        // 3. 检查属性名是否包含密码/凭据敏感关键字
        if (propName != null && !propName.isBlank()) {
            String lower = propName.toLowerCase();
            return lower.contains("password") ||
                    lower.contains("pwd") ||
                    lower.contains("secret") ||
                    lower.contains("token") ||
                    lower.contains("credential") ||
                    (lower.contains("key") && lower.contains("private"));
        }

        return false;
    }

    /**
     * 对 SQL 进行多行缩进美化排版
     * 智能识别并绝对保护字符串字面量、双引号标识符与 SQL 注释，
     * 仅对外部 SQL 关键字执行格式化与缩进，彻底杜绝破坏字面量内部内容。
     *
     * @param sql 原始 SQL
     * @return 美化后的 SQL
     */
    public static String prettify(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        // 先进行字面量保护的单行规范化
        String compressed = compress(sql);
        List<SqlSegment> segments = splitSegments(compressed);
        StringBuilder sb = new StringBuilder(compressed.length() + 64);
        for (SqlSegment seg : segments) {
            if (seg.isLiteral()) {
                sb.append(seg.text());
            } else {
                String formatted = seg.text()
                        .replaceAll("(?i)\\b(SELECT|FROM|WHERE|GROUP BY|HAVING|ORDER BY|LIMIT|INSERT INTO|VALUES|UPDATE|SET|DELETE FROM)\\b", "\n  $1")
                        .replaceAll("(?i)\\b(AND|OR)\\b", "\n    $1");
                sb.append(formatted);
            }
        }
        return sb.toString().trim();
    }

    /**
     * 将 SQL 压缩为紧凑单行格式
     * 智能识别并绝对保护字符串字面量、双引号标识符与 SQL 注释内部的连续空白，仅压缩外部多余空白。
     *
     * @param sql 原始 SQL
     * @return 单行压缩 SQL
     */
    public static String compress(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        List<SqlSegment> segments = splitSegments(sql);
        StringBuilder sb = new StringBuilder(sql.length());
        for (SqlSegment seg : segments) {
            if (seg.isLiteral()) {
                sb.append(seg.text());
            } else {
                String code = seg.text().replaceAll("\\s+", " ");
                if (!sb.isEmpty() && Character.isWhitespace(sb.charAt(sb.length() - 1)) && code.startsWith(" ")) {
                    code = code.substring(1);
                }
                sb.append(code);
            }
        }
        while (!sb.isEmpty() && Character.isWhitespace(sb.charAt(0))) {
            sb.deleteCharAt(0);
        }
        while (!sb.isEmpty() && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * SQL 语法片段，区分普通代码区与字面量保护区
     *
     * @param text 片段文本
     * @param isLiteral 是否为字面量（字符串常量、标识符、SQL 注释）
     */
    private record SqlSegment(String text, boolean isLiteral) {}

    /**
     * 将 SQL 字符串拆分为代码片段与字面量片段序列
     */
    private static List<SqlSegment> splitSegments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return Collections.emptyList();
        }
        List<SqlSegment> segments = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        int codeStart = 0;

        while (i < len) {
            char c = sql.charAt(i);

            // 1. 检查单行注释 --
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                if (i > codeStart) {
                    segments.add(new SqlSegment(sql.substring(codeStart, i), false));
                }
                int literalStart = i;
                i += 2;
                while (i < len && sql.charAt(i) != '\n') {
                    i++;
                }
                if (i < len && sql.charAt(i) == '\n') {
                    i++;
                }
                segments.add(new SqlSegment(sql.substring(literalStart, i), true));
                codeStart = i;
                continue;
            }

            // 2. 检查多行注释 /* ... */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                if (i > codeStart) {
                    segments.add(new SqlSegment(sql.substring(codeStart, i), false));
                }
                int literalStart = i;
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 < len) {
                    i += 2;
                } else {
                    i = len;
                }
                segments.add(new SqlSegment(sql.substring(literalStart, i), true));
                codeStart = i;
                continue;
            }

            // 3. 检查单引号字符串常量 '...'
            if (c == '\'') {
                if (i > codeStart) {
                    segments.add(new SqlSegment(sql.substring(codeStart, i), false));
                }
                int literalStart = i;
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (ch == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                segments.add(new SqlSegment(sql.substring(literalStart, i), true));
                codeStart = i;
                continue;
            }

            // 4. 检查双引号标识符 "..."
            if (c == '"') {
                if (i > codeStart) {
                    segments.add(new SqlSegment(sql.substring(codeStart, i), false));
                }
                int literalStart = i;
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (ch == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                segments.add(new SqlSegment(sql.substring(literalStart, i), true));
                codeStart = i;
                continue;
            }

            // 5. 检查反引号标识符 `...`
            if (c == '`') {
                if (i > codeStart) {
                    segments.add(new SqlSegment(sql.substring(codeStart, i), false));
                }
                int literalStart = i;
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '`') {
                        if (i + 1 < len && sql.charAt(i + 1) == '`') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                segments.add(new SqlSegment(sql.substring(literalStart, i), true));
                codeStart = i;
                continue;
            }

            i++;
        }

        if (codeStart < len) {
            segments.add(new SqlSegment(sql.substring(codeStart, len), false));
        }

        return segments;
    }

    private static String escapeSql(String s) {
        return s.replace("'", "''");
    }

    private static int countPrecedingBackslashes(String str, int index) {
        int count = 0;
        int k = index - 1;
        while (k >= 0 && str.charAt(k) == '\\') {
            count++;
            k--;
        }
        return count;
    }

    private static boolean isFieldEncrypted(Object parameterObject, String propName) {
        if (parameterObject == null || propName == null || propName.isBlank()) {
            return false;
        }
        Class<?> targetClass = parameterObject.getClass();
        String fieldName = propName;

        if (propName.contains(".")) {
            String[] parts = propName.split("\\.");
            Object currentObj = parameterObject;
            for (int i = 0; i < parts.length - 1; i++) {
                if (currentObj instanceof Map<?, ?> map) {
                    currentObj = map.get(parts[i]);
                } else if (currentObj != null) {
                    try {
                        MetaObject mo = SystemMetaObject.forObject(currentObj);
                        if (mo.hasGetter(parts[i])) {
                            currentObj = mo.getValue(parts[i]);
                        } else {
                            currentObj = null;
                        }
                    } catch (Exception ignored) {
                        currentObj = null;
                    }
                }
                if (currentObj == null) {
                    break;
                }
            }
            if (currentObj != null) {
                targetClass = currentObj.getClass();
            }
            fieldName = parts[parts.length - 1];
        }

        return checkFieldEncryptAnnotation(targetClass, fieldName);
    }

    private static boolean checkFieldEncryptAnnotation(Class<?> entityClass, String propName) {
        if (entityClass == null || propName == null || propName.isBlank()) {
            return false;
        }
        String cacheKey = entityClass.getName() + "#" + propName;
        return FIELD_ENCRYPT_CACHE.computeIfAbsent(cacheKey, k -> {
            Class<?> current = entityClass;
            while (current != null && current != Object.class) {
                try {
                    Field field = current.getDeclaredField(propName);
                    if (field.isAnnotationPresent(FieldEncrypt.class)) {
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                current = current.getSuperclass();
            }
            return false;
        });
    }
}
