package io.github.atengk.boot.mybatisplus.dynamicquery;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部/XML 动态 SQL 的 AST 投影解析引擎
 * 依托 JSqlParser 静态提取 SELECT 列表中的别名与底层物理列/表达式映射字典，并进行纳秒级内存缓存，
 * 实现多表联表查询中表别名自愈抹平，使前端传输边界与 VO 保持 100% 纯 POJO 零注解解耦。
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public final class DynamicSqlProjectionResolver {

    /**
     * 投影映射字典缓存（ConcurrentHashMap 纳秒级快照）
     * key 为 statementId 或 SQL 文本
     */
    private static final Map<String, Map<String, String>> PROJECTION_CACHE = new ConcurrentHashMap<>();

    private DynamicSqlProjectionResolver() {
    }

    /**
     * 基于 SQL 文本静态分析 SELECT 投影并生成归一化属性映射字典（全自动快照缓存）
     *
     * @param sql 待解析的 SQL 语句
     * @return 归一化属性名至底层物理列或表达式的映射字典（不可变 Map）
     */
    public static Map<String, String> resolveProjections(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return PROJECTION_CACHE.computeIfAbsent(sql.trim(), DynamicSqlProjectionResolver::parseSelectProjections);
    }

    /**
     * 基于 StatementId 与 SQL 文本静态分析 SELECT 投影并生成归一化属性映射字典（以 StatementId 为主键缓存）
     *
     * @param statementId MyBatis Statement 唯一标识（优先作为缓存键）
     * @param sql         待解析的 SQL 语句
     * @return 归一化属性名至底层物理列或表达式的映射字典（不可变 Map）
     */
    public static Map<String, String> resolveProjections(String statementId, String sql) {
        if (statementId != null && !statementId.trim().isEmpty()) {
            return PROJECTION_CACHE.computeIfAbsent(statementId.trim(), key -> parseSelectProjections(sql));
        }
        return resolveProjections(sql);
    }

    /**
     * 刷新或更新目标 StatementId 的投影映射缓存（供动态 SQL 热替换即时生效）
     *
     * @param statementId 语句唯一标识
     * @param sql         最新的 SQL 脚本文本
     * @return 刷新后的投影映射字典
     */
    public static Map<String, String> refreshProjections(String statementId, String sql) {
        if (statementId == null || statementId.trim().isEmpty()) {
            return resolveProjections(sql);
        }
        Map<String, String> mapping = parseSelectProjections(sql);
        PROJECTION_CACHE.put(statementId.trim(), mapping);
        return mapping;
    }

    /**
     * 从全局缓存中精准移除指定 StatementId 的投影映射条目
     *
     * @param statementId 语句唯一标识
     */
    public static void invalidate(String statementId) {
        if (statementId != null && !statementId.trim().isEmpty()) {
            PROJECTION_CACHE.remove(statementId.trim());
        }
    }

    /**
     * 清空投影映射字典解析缓存（供动态脚本热加载或单元测试隔离）
     */
    public static void clearCache() {
        PROJECTION_CACHE.clear();
    }

    /**
     * 获取当前投影映射字典缓存容量
     *
     * @return 缓存中已解析的条目数量
     */
    public static int cacheSize() {
        return PROJECTION_CACHE.size();
    }

    /**
     * 从投影映射字典中智能寻找目标属性匹配的底层物理列表达式
     * 支持驼峰、下划线、全小写等多种形态自愈匹配
     *
     * @param mapping  投影映射字典
     * @param property 目标属性名称（如 deptName 或 dept_name）
     * @return 底层物理列表达式（如 d.name），未命中时返回 null
     */
    public static String findColumn(Map<String, String> mapping, String property) {
        if (mapping == null || mapping.isEmpty() || property == null || property.trim().isEmpty()) {
            return null;
        }
        String prop = property.trim();
        // 1. 原始精确匹配
        String col = mapping.get(prop);
        if (col != null) {
            return col;
        }
        // 2. 全小写匹配
        col = mapping.get(prop.toLowerCase(Locale.ROOT));
        if (col != null) {
            return col;
        }
        // 3. 驼峰转下划线匹配
        String underline = camelToUnderline(prop);
        col = mapping.get(underline);
        if (col != null) {
            return col;
        }
        // 4. 下划线转驼峰匹配
        String camel = underlineToCamel(prop);
        col = mapping.get(camel);
        if (col != null) {
            return col;
        }
        // 5. 去除下划线小写匹配
        col = mapping.get(prop.replace("_", "").toLowerCase(Locale.ROOT));
        return col;
    }

    /**
     * 清洗待解析 SQL 中的 XML 标签、MyBatis 动态标签及 #{...} / ${...} 占位符，以供 JSqlParser 纯粹语法解析
     *
     * @param sql 原始 SQL 或包含动态标签的脚本
     * @return 适合 JSqlParser 解析的纯净 SQL
     */
    public static String cleanSqlForAstParsing(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "";
        }
        String cleaned = sql;
        // 1. 去除 XML / MyBatis 标签（如 <script>, </script>, <where>, <if test="..."> 等）
        cleaned = cleaned.replaceAll("<[^>]+>", " ");
        // 2. 替换 #{...} 参数占位符为 0，使语法合法
        cleaned = cleaned.replaceAll("#\\{[^}]+\\}", "0");
        // 3. 移除 ${...} 动态拼接占位符（如 ${ew.customSqlSegment}）
        cleaned = cleaned.replaceAll("\\$\\{[^}]+\\}", " ");
        return cleaned.trim();
    }

    /**
     * 依托 JSqlParser 深度分析 SQL 的 SELECT 投影
     */
    private static Map<String, String> parseSelectProjections(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            String sanitizedSql = cleanSqlForAstParsing(sql);
            Statement statement = CCJSqlParserUtil.parse(sanitizedSql);
            if (!(statement instanceof Select select)) {
                log.debug("SQL 语句不是 SELECT 查询，跳过投影解析: {}", sql);
                return Collections.emptyMap();
            }
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect == null) {
                log.debug("SELECT 语句未包含标准 PlainSelect 结构，跳过投影解析: {}", sql);
                return Collections.emptyMap();
            }
            List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
            if (selectItems == null || selectItems.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, String> mapping = new HashMap<>();
            for (SelectItem<?> item : selectItems) {
                if (item == null) {
                    continue;
                }
                net.sf.jsqlparser.expression.Alias alias = item.getAlias();
                if (alias != null && alias.getName() != null && !alias.getName().trim().isEmpty()) {
                    String aliasName = cleanIdentifier(alias.getName().trim());
                    String exprStr = item.getExpression() != null ? item.getExpression().toString().trim() : aliasName;
                    registerAliasVariants(mapping, aliasName, exprStr);
                } else if (item.getExpression() instanceof Column column) {
                    String colName = cleanIdentifier(column.getColumnName());
                    String exprStr = column.toString().trim();
                    registerAliasVariants(mapping, colName, exprStr);
                }
            }
            return Collections.unmodifiableMap(mapping);
        } catch (Throwable e) {
            log.warn("JSqlParser 解析 SQL 投影失败，安全回退为空映射: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 清理标识符两端的转义引号（如反引号、双引号、单引号或方括号）
     */
    private static String cleanIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String trimmed = identifier.trim();
        if ((trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /**
     * 注册别名/属性名称的各种变体（驼峰、下划线、全小写等）到映射表
     */
    private static void registerAliasVariants(Map<String, String> mapping, String name, String expr) {
        if (name == null || name.isEmpty() || expr == null || expr.isEmpty()) {
            return;
        }
        mapping.put(name, expr);
        mapping.put(name.toLowerCase(Locale.ROOT), expr);

        String camel = underlineToCamel(name);
        mapping.put(camel, expr);
        mapping.put(camel.toLowerCase(Locale.ROOT), expr);

        String underline = camelToUnderline(name);
        mapping.put(underline, expr);
        mapping.put(underline.toLowerCase(Locale.ROOT), expr);

        // 去下划线全小写
        mapping.put(name.replace("_", "").toLowerCase(Locale.ROOT), expr);
    }

    /**
     * 驼峰转下划线（纯原生零臃肿）
     */
    public static String camelToUnderline(String param) {
        if (param == null || param.isEmpty()) {
            return "";
        }
        int len = param.length();
        StringBuilder sb = new StringBuilder(len + 5);
        for (int i = 0; i < len; i++) {
            char c = param.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && param.charAt(i - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 下划线转驼峰（纯原生零臃肿）
     */
    public static String underlineToCamel(String param) {
        if (param == null || param.isEmpty()) {
            return "";
        }
        String temp = param.toLowerCase(Locale.ROOT);
        int len = temp.length();
        StringBuilder sb = new StringBuilder(len);
        boolean upperCase = false;
        for (int i = 0; i < len; i++) {
            char c = temp.charAt(i);
            if (c == '_') {
                upperCase = true;
            } else {
                if (upperCase) {
                    sb.append(Character.toUpperCase(c));
                    upperCase = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
