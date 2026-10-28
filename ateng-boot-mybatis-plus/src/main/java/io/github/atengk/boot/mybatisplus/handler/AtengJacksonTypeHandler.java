package io.github.atengk.boot.mybatisplus.handler;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.JsonUtil;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产加固型受管 JSON 字段类型处理器
 * 自动绑定 Spring Boot 受管 ObjectMapper（集成 JavaTimeModule 与全局脱敏契约），
 * 反序列化时对 List/Set/Map 属性空值保底返回不可变空容器，彻底杜绝 NPE 与 LocalDateTime 序列化崩溃。
 *
 * @param <T> 实体属性目标类型
 * @author Ateng
 * @since 2026-10-27
 */
@MappedJdbcTypes(value = {JdbcType.VARCHAR, JdbcType.OTHER}, includeNullJdbcType = true)
@MappedTypes({Object.class})
public class AtengJacksonTypeHandler<T> extends JacksonTypeHandler {

    private static final Logger log = LoggerFactory.getLogger(AtengJacksonTypeHandler.class);

    static {
        // 静态预初始化：使用 Core 加固的 ObjectMapper（标配 JavaTimeModule、东八区时区）预设
        try {
            JacksonTypeHandler.setObjectMapper(JsonUtil.getObjectMapper());
        } catch (Throwable e) {
            log.debug("静态预设 ObjectMapper 忽略异常: {}", e.getMessage());
        }
    }

    protected final Class<?> targetClass;
    protected Type targetType;

    public AtengJacksonTypeHandler(Class<?> type) {
        super(type);
        this.targetClass = type;
        this.targetType = type;
    }

    public AtengJacksonTypeHandler(Class<?> type, Field field) {
        super(type, field);
        this.targetClass = type;
        this.targetType = (field != null) ? field.getGenericType() : type;
    }

    protected AtengJacksonTypeHandler(Class<?> type, Type genericType) {
        super(type);
        this.targetClass = type;
        this.targetType = (genericType != null) ? genericType : type;
        this.genericType = this.targetType;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, toJson(parameter));
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        if (StringUtils.isBlank(json)) {
            return resolveDefaultValue();
        }
        return parse(json);
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        if (StringUtils.isBlank(json)) {
            return resolveDefaultValue();
        }
        return parse(json);
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        if (StringUtils.isBlank(json)) {
            return resolveDefaultValue();
        }
        return parse(json);
    }

    @Override
    public Object parse(String json) {
        if (StringUtils.isBlank(json)) {
            return resolveDefaultValue();
        }
        try {
            Type fieldType = getFieldType();
            ObjectMapper mapper = resolveObjectMapper();
            JavaType javaType = mapper.getTypeFactory().constructType(fieldType);
            Object result = mapper.readValue(json, javaType);
            return result != null ? result : resolveDefaultValue();
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + json, e);
        }
    }

    @Override
    public String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return resolveObjectMapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("对象序列化为 JSON 失败", e);
        }
    }

    @Override
    public Type getFieldType() {
        if (this.targetType != null) {
            return this.targetType;
        }
        return super.getFieldType();
    }

    /**
     * 获取受管 ObjectMapper，若未显式配置则保底回退至 Core 的加固 JsonUtil ObjectMapper
     *
     * @return 当前生效的 ObjectMapper 实例
     */
    protected ObjectMapper resolveObjectMapper() {
        ObjectMapper mapper = getObjectMapper();
        return mapper != null ? mapper : JsonUtil.getObjectMapper();
    }

    /**
     * 根据目标属性类型安全推导默认保底值（列表/集合/Map 保底返回不可变空容器，其他返回 null）
     *
     * @return 空安全保底默认值
     */
    protected Object resolveDefaultValue() {
        Class<?> rawClass = resolveRawClass(getFieldType());
        if (rawClass != null) {
            if (List.class.isAssignableFrom(rawClass)) {
                return Collections.emptyList();
            }
            if (Set.class.isAssignableFrom(rawClass)) {
                return Collections.emptySet();
            }
            if (Map.class.isAssignableFrom(rawClass)) {
                return Collections.emptyMap();
            }
        }
        return null;
    }

    private Class<?> resolveRawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        return targetClass;
    }
}
