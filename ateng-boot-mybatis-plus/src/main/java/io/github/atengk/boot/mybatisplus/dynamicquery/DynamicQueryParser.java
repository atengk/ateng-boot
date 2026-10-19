package io.github.atengk.boot.mybatisplus.dynamicquery;

import io.github.atengk.boot.core.model.SortItem;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL Query 冒号高阶语法与作用域 OR 分组参数解析器
 * 解析 HTTP GET 请求 URL 查询字符串或参数 Map，支持冒号操作符、波浪线 between 语法及 or_组号 嵌套分组
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public final class DynamicQueryParser {

    /**
     * or_组号: 作用域前缀正则（如 or_1:name:like=test -> 匹配出组号 1 与后续表达式）
     */
    private static final Pattern OR_GROUP_PREFIX_PATTERN = Pattern.compile("^or_([a-zA-Z0-9]+):(.*)$", Pattern.CASE_INSENSITIVE);

    /**
     * 分页当前页码参数候选名集合
     */
    private static final List<String> PAGE_NUM_PARAMS = List.of("current", "pagenum", "page");

    /**
     * 分页每页条数参数候选名集合
     */
    private static final List<String> PAGE_SIZE_PARAMS = List.of("size", "pagesize", "limit");

    /**
     * 排序参数候选名集合
     */
    private static final List<String> SORT_PARAMS = List.of("sort", "sorts", "orderby");

    private DynamicQueryParser() {
    }

    /**
     * 解析 HTTP GET URL Query 字符串为 DynamicQueryRequest
     *
     * @param queryString 原始查询字符串（如 "current=1&size=20&name:like=admin&or_1:status=1&or_1:role=admin"）
     * @return 解析完成的 DynamicQueryRequest 实例
     */
    public static DynamicQueryRequest parse(String queryString) {
        DynamicQueryRequest request = new DynamicQueryRequest();
        if (queryString == null || queryString.trim().isEmpty()) {
            return request;
        }

        String rawQuery = queryString.trim();
        if (rawQuery.startsWith("?")) {
            rawQuery = rawQuery.substring(1);
        }

        Map<String, List<String>> paramMap = new LinkedHashMap<>();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key;
            String val = "";
            if (idx > 0) {
                key = decode(pair.substring(0, idx));
                val = decode(pair.substring(idx + 1));
            } else {
                key = decode(pair);
            }
            if (key != null && !key.trim().isEmpty()) {
                paramMap.computeIfAbsent(key.trim(), k -> new ArrayList<>()).add(val.trim());
            }
        }

        parseInternal(request, paramMap);
        return request;
    }

    /**
     * 解析请求参数 Map 为 DynamicQueryRequest（适配 Servlet/Spring parameterMap）
     *
     * @param parameterMap 请求参数 Map（支持 Map&lt;String, String[]&gt;、Map&lt;String, List&lt;String&gt;&gt; 或 Map&lt;String, Object&gt;）
     * @return 解析完成的 DynamicQueryRequest 实例
     */
    public static DynamicQueryRequest parse(Map<String, ?> parameterMap) {
        DynamicQueryRequest request = new DynamicQueryRequest();
        if (parameterMap == null || parameterMap.isEmpty()) {
            return request;
        }

        Map<String, List<String>> normalizedMap = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : parameterMap.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            Object rawVal = entry.getValue();
            List<String> values = extractStringList(rawVal);
            normalizedMap.put(key.trim(), values);
        }

        parseInternal(request, normalizedMap);
        return request;
    }

    /**
     * 核心内部参数解析流程
     */
    private static void parseInternal(DynamicQueryRequest request, Map<String, List<String>> paramMap) {
        for (Map.Entry<String, List<String>> entry : paramMap.entrySet()) {
            String rawKey = entry.getKey();
            List<String> values = entry.getValue();
            String firstVal = (values != null && !values.isEmpty()) ? values.get(0) : "";

            String lowerKey = rawKey.toLowerCase();

            // 1. 优先提取分页参数
            if (PAGE_NUM_PARAMS.contains(lowerKey)) {
                try {
                    long current = Long.parseLong(firstVal);
                    if (current > 0) {
                        request.setCurrent(current);
                    }
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            if (PAGE_SIZE_PARAMS.contains(lowerKey)) {
                try {
                    long size = Long.parseLong(firstVal);
                    if (size > 0) {
                        request.setSize(size);
                    }
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            // 2. 提取排序参数 (如 sort=createTime:desc,id:asc 或多组 sort 参数)
            if (SORT_PARAMS.contains(lowerKey)) {
                if (values != null) {
                    for (String sortVal : values) {
                        parseSortExpression(request, sortVal);
                    }
                }
                continue;
            }

            // 3. 解析动态查询条件
            parseConditionExpression(request, rawKey, values);
        }
    }

    /**
     * 解析排序表达式并安全追加至 request
     */
    private static void parseSortExpression(DynamicQueryRequest request, String sortExpression) {
        if (sortExpression == null || sortExpression.trim().isEmpty()) {
            return;
        }
        // 支持逗号分隔的多字段排序 (如 "createTime:desc,id:asc")
        String[] items = sortExpression.split(",");
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            String property = parts[0].trim();
            if (!SortItem.isValidProperty(property)) {
                continue;
            }
            boolean isAsc = true;
            if (parts.length > 1) {
                String dirStr = parts[1].trim();
                isAsc = !"desc".equalsIgnoreCase(dirStr);
            }
            request.addSort(property, isAsc);
        }
    }

    /**
     * 解析单个或多值动态查询条件表达式
     */
    private static void parseConditionExpression(DynamicQueryRequest request, String rawKey, List<String> values) {
        String orGroup = null;
        String remainingKey = rawKey;

        // 1. 识别 or_组号: 作用域前缀
        Matcher matcher = OR_GROUP_PREFIX_PATTERN.matcher(rawKey);
        if (matcher.matches()) {
            orGroup = matcher.group(1);
            remainingKey = matcher.group(2);
        }

        // 2. 识别 field:operator 语法
        String field;
        QueryOperator operator = QueryOperator.EQ;

        int colonIdx = remainingKey.indexOf(':');
        if (colonIdx > 0) {
            field = remainingKey.substring(0, colonIdx).trim();
            String opStr = remainingKey.substring(colonIdx + 1).trim();
            try {
                operator = QueryOperator.fromString(opStr);
            } catch (IllegalArgumentException e) {
                log.warn("URL 查询条件操作符非法，已安全忽略: {}", rawKey);
                return;
            }
        } else {
            field = remainingKey.trim();
        }

        if (!SortItem.isValidProperty(field)) {
            log.warn("URL 查询条件属性名称不符合安全白名单，已安全忽略: {}", field);
            return;
        }

        String singleVal = (values != null && !values.isEmpty()) ? values.get(0) : "";

        // 3. 根据操作符构建 QueryCondition
        QueryCondition condition = buildConditionByOperator(orGroup, field, operator, singleVal, values);
        if (condition != null) {
            request.addCondition(condition);
        }
    }

    /**
     * 根据操作符特征装配 QueryCondition 实例并处理边界切分
     */
    private static QueryCondition buildConditionByOperator(String orGroup, String field, QueryOperator operator,
                                                           String singleVal, List<String> allValues) {
        switch (operator) {
            case IS_NULL, IS_NOT_NULL -> {
                // 无值操作符，直接装配
                QueryCondition cond = new QueryCondition(orGroup, field, operator, null);
                return cond;
            }
            case BETWEEN -> {
                // 波浪线 ~ 优先切分，防空格与冒号错乱
                List<Object> rangeValues = new ArrayList<>(2);
                if (singleVal != null && !singleVal.isEmpty()) {
                    if (singleVal.contains("~")) {
                        String[] range = singleVal.split("~", 2);
                        if (range.length == 2 && !range[0].trim().isEmpty() && !range[1].trim().isEmpty()) {
                            rangeValues.add(range[0].trim());
                            rangeValues.add(range[1].trim());
                        }
                    } else if (singleVal.contains(",")) {
                        String[] range = singleVal.split(",", 2);
                        if (range.length == 2 && !range[0].trim().isEmpty() && !range[1].trim().isEmpty()) {
                            rangeValues.add(range[0].trim());
                            rangeValues.add(range[1].trim());
                        }
                    }
                }
                if (rangeValues.size() < 2) {
                    log.warn("BETWEEN 操作符参数格式非法（必须包含 ~ 或 , 双边界），已安全忽略: {}", singleVal);
                    return null;
                }
                QueryCondition cond = new QueryCondition(orGroup, field, operator, null);
                cond.setValues(rangeValues);
                return cond;
            }
            case IN, NOT_IN -> {
                // 逗号分隔切分或多元素展开
                List<Object> inValues = new ArrayList<>();
                if (allValues != null && allValues.size() > 1) {
                    for (String val : allValues) {
                        if (val != null && !val.trim().isEmpty()) {
                            inValues.add(val.trim());
                        }
                    }
                } else if (singleVal != null && !singleVal.isEmpty()) {
                    String[] items = singleVal.split(",");
                    for (String item : items) {
                        if (!item.trim().isEmpty()) {
                            inValues.add(item.trim());
                        }
                    }
                }
                if (inValues.isEmpty()) {
                    return null;
                }
                QueryCondition cond = new QueryCondition(orGroup, field, operator, null);
                cond.setValues(inValues);
                return cond;
            }
            default -> {
                // 普通单值操作符
                if (singleVal == null || singleVal.trim().isEmpty()) {
                    return null;
                }
                return new QueryCondition(orGroup, field, operator, singleVal.trim());
            }
        }
    }

    /**
     * 将对象安全转为字符串列表
     */
    private static List<String> extractStringList(Object rawVal) {
        if (rawVal == null) {
            return Collections.emptyList();
        }
        if (rawVal instanceof String[] arr) {
            return Arrays.asList(arr);
        }
        if (rawVal instanceof Collection<?> col) {
            List<String> list = new ArrayList<>();
            for (Object o : col) {
                if (o != null) {
                    list.add(o.toString());
                }
            }
            return list;
        }
        return List.of(rawVal.toString());
    }

    /**
     * 安全 URL 解码
     */
    private static String decode(String val) {
        if (val == null) {
            return null;
        }
        try {
            return URLDecoder.decode(val, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return val;
        }
    }
}
