package io.github.atengk.boot.mybatisplus.dynamicsql;

import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部动态 SQL 注册与热替换中心
 * 负责将 DynamicSqlDefinition 预编译并原子注入或刷新至 MyBatis Configuration，解除 StrictMap 冲突限制
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class DynamicSqlRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicSqlRegistry.class);

    private final Configuration configuration;
    private final ExternalSqlLoader loader;
    private final Map<String, DynamicSqlDefinition> definitions = new ConcurrentHashMap<>();
    private final Field mappedStatementsField;
    private final boolean defaultAutoScriptWrap;

    public DynamicSqlRegistry(Configuration configuration) {
        this(configuration, null, true);
    }

    public DynamicSqlRegistry(Configuration configuration, ExternalSqlLoader loader) {
        this(configuration, loader, true);
    }

    public DynamicSqlRegistry(Configuration configuration, ExternalSqlLoader loader, boolean defaultAutoScriptWrap) {
        this.configuration = Objects.requireNonNull(configuration, "Configuration must not be null");
        this.loader = loader;
        this.defaultAutoScriptWrap = defaultAutoScriptWrap;
        this.mappedStatementsField = resolveMappedStatementsField(configuration);
    }

    /**
     * 注册或原子热替换单个动态 SQL 定义
     *
     * @param definition 动态 SQL 契约实体
     * @return 注册/热更新成功返回 true；语法校验失败时返回 false 并自动保留旧版本
     */
    public synchronized boolean register(DynamicSqlDefinition definition) {
        if (definition == null) {
            return false;
        }
        String id = definition.getId();
        try {
            // 1. 在内存沙箱中尝试预编译构建 MappedStatement（语法损坏时在此处 Fail-Fast 抛出异常）
            MappedStatement ms = buildMappedStatement(definition);

            // 2. 原子注入或热覆盖进 MyBatis Configuration 容器（彻底消除 StrictMap 冲突与读并发瞬态空隙）
            safelyPutStatement(id, ms);

            // 3. 更新本地元数据快照
            definitions.put(id, definition);
            log.info("外部动态 SQL [{}] 成功注册/热更新至持久层容器", id);
            return true;
        } catch (Exception e) {
            log.error("外部动态 SQL [{}] 预编译/注册失败，系统已安全拦截并保留上一版本状态: {}", id, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 动态卸载并移除指定 ID 的 SQL 语句
     *
     * @param id 语句唯一标识
     * @return 移除成功返回 true；不存在返回 false
     */
    public synchronized boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        boolean exists = hasStatement(id) || definitions.containsKey(id);
        if (!exists) {
            return false;
        }
        removeStatementFromConfiguration(id);
        definitions.remove(id);
        log.info("外部动态 SQL [{}] 已成功从持久层容器中注销", id);
        return true;
    }

    /**
     * 依托绑定的 ExternalSqlLoader 全量拉取并批量注册所有动态 SQL
     *
     * @return 成功注册的语句总数
     */
    public int loadAndRegisterAll() {
        if (loader == null) {
            log.debug("未注入 ExternalSqlLoader，跳过全量加载步骤");
            return 0;
        }
        List<DynamicSqlDefinition> list = loader.loadAll();
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (DynamicSqlDefinition def : list) {
            if (register(def)) {
                count++;
            }
        }
        log.info("外部动态 SQL 全量预热加载完毕，成功装载 {}/{} 条定义", count, list.size());
        return count;
    }

    /**
     * 针对指定 ID 触发增量热刷新
     *
     * @param id 语句唯一标识
     * @return 刷新成功返回 true
     */
    public boolean refresh(String id) {
        if (loader == null || id == null || id.isBlank()) {
            return false;
        }
        Optional<DynamicSqlDefinition> defOpt = loader.loadById(id);
        if (defOpt.isEmpty()) {
            log.warn("无法从 ExternalSqlLoader 获取到 SQL [{}] 的最新定义，热更新已终止", id);
            return false;
        }
        return register(defOpt.get());
    }

    /**
     * 触发全量动态 SQL 热刷新
     *
     * @return 成功刷新的语句数量
     */
    public int refreshAll() {
        return loadAndRegisterAll();
    }

    /**
     * 检查当前持久层容器中是否存在指定 Statement
     *
     * @param id 语句唯一标识
     * @return 存在返回 true
     */
    public boolean hasStatement(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return configuration.hasStatement(id, false);
    }

    /**
     * 获取指定 ID 的动态 SQL 元数据定义
     *
     * @param id 语句唯一标识
     * @return 元数据定义（不存在时返回 null）
     */
    public DynamicSqlDefinition getDefinition(String id) {
        return (id != null) ? definitions.get(id) : null;
    }

    /**
     * 获取所有已注册的动态 SQL 元数据定义只读集合
     *
     * @return 元数据集合
     */
    public Collection<DynamicSqlDefinition> getAllDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    /**
     * 基于 DynamicSqlDefinition 预编译构建 MyBatis 标准 MappedStatement
     */
    private MappedStatement buildMappedStatement(DynamicSqlDefinition definition) {
        String id = definition.getId();
        String script = DynamicSqlDefinition.wrapScriptIfNecessary(definition.getSqlScript(), defaultAutoScriptWrap);
        LanguageDriver languageDriver = configuration.getDefaultScriptingLanguageInstance();
        SqlSource sqlSource = languageDriver.createSqlSource(configuration, script, Object.class);

        MappedStatement.Builder builder = new MappedStatement.Builder(
                configuration,
                id,
                sqlSource,
                definition.getCommandType()
        );
        builder.resource("DynamicSql: " + id);

        if (definition.getCommandType() == SqlCommandType.SELECT) {
            Class<?> resultType = definition.getResultType();
            if (resultType == null) {
                resultType = Map.class;
            }
            String resultMapId = id + "-InlineResultMap";
            ResultMap inlineResultMap = new ResultMap.Builder(
                    configuration,
                    resultMapId,
                    resultType,
                    Collections.emptyList(),
                    null
            ).build();
            builder.resultMaps(List.of(inlineResultMap));
        }

        return builder.build();
    }

    /**
     * 安全注入或原子热覆盖 MappedStatement
     * 规避 StrictMap duplicate key 异常，并彻底消除 remove 导致的读并发毫秒级空隙
     */
    @SuppressWarnings("unchecked")
    private void safelyPutStatement(String id, MappedStatement ms) {
        if (mappedStatementsField != null) {
            try {
                Map<String, MappedStatement> map = (Map<String, MappedStatement>) mappedStatementsField.get(configuration);
                if (map != null) {
                    // 若已存在旧映射，利用 HashMap.putAll 无锁原子覆盖已有条目，避免调用覆写了 put 检查的 StrictMap
                    if (map.containsKey(id)) {
                        Map<String, MappedStatement> patch = new HashMap<>();
                        patch.put(id, ms);
                        if (id.contains(".")) {
                            String shortKey = id.substring(id.lastIndexOf('.') + 1);
                            patch.put(shortKey, ms);
                        }
                        map.putAll(patch);
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("putAll 原子覆盖尝试失败，将回退至安全注册机制: {}", e.getMessage());
            }
        }
        // 初次注册，使用官方 addMappedStatement
        configuration.addMappedStatement(ms);
    }

    /**
     * 从 MyBatis Configuration 的 mappedStatements 中受控移除旧 Statement
     */
    private void removeStatementFromConfiguration(String id) {
        if (mappedStatementsField == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) mappedStatementsField.get(configuration);
            if (map != null) {
                map.remove(id);
                if (id.contains(".")) {
                    String shortName = id.substring(id.lastIndexOf('.') + 1);
                    map.remove(shortName);
                }
            }
        } catch (Exception e) {
            log.debug("反射安全清理旧 MappedStatement 条目已跳过: {}", e.getMessage());
        }
    }

    /**
     * 解析 Configuration.mappedStatements 反射字段
     */
    private static Field resolveMappedStatementsField(Configuration configuration) {
        Class<?> clazz = configuration.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField("mappedStatements");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        log.warn("未能定位 Configuration.mappedStatements 字段，同 ID 热替换可能触发 StrictMap 冲突告警");
        return null;
    }
}
