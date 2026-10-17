package io.github.atengk.boot.mybatisplus.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 默认行级多租户处理器实现
 * 负责从 TenantContextHolder 提取当前租户并构建自适应 JSqlParser 表达式，维护表白名单比对
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DefaultTenantLineHandler implements TenantLineHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantLineHandler.class);

    private final String tenantColumn;
    private final Set<String> ignoreTableSet;

    public DefaultTenantLineHandler(MybatisPlusProperties.TenantProperties properties) {
        this.tenantColumn = (properties != null && properties.getColumn() != null && !properties.getColumn().isBlank())
                ? properties.getColumn().trim()
                : "tenant_id";
        if (properties != null && properties.getIgnoreTables() != null && !properties.getIgnoreTables().isEmpty()) {
            Set<String> set = new HashSet<>();
            for (String table : properties.getIgnoreTables()) {
                if (table != null && !table.isBlank()) {
                    set.add(cleanTableName(table));
                }
            }
            this.ignoreTableSet = Collections.unmodifiableSet(set);
        } else {
            this.ignoreTableSet = Collections.emptySet();
        }
    }

    public DefaultTenantLineHandler() {
        this(new MybatisPlusProperties.TenantProperties());
    }

    @Override
    public Expression getTenantId() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            if (!TenantContextHolder.isIgnoreTenant()) {
                log.warn("多租户环境上下文未获取到有效的 TenantId，将回退为 NULL 表达式进行条件匹配 (tenant_id IS NULL 或插入 NULL)");
            }
            return new NullValue();
        }

        // 智能自适应推断类型：纯数字优先构建 LongValue，杜绝数据库列隐式转换导致索引失效
        if (isNumeric(tenantId)) {
            try {
                return new LongValue(Long.parseLong(tenantId));
            } catch (NumberFormatException ignored) {
                // 超出 64 位 Long 范围时安全回退为 StringValue
                return new StringValue(tenantId);
            }
        }
        return new StringValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return tenantColumn;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        String cleanName = cleanTableName(tableName);
        return ignoreTableSet.contains(cleanName);
    }

    /**
     * 清理表名中的反引号、双引号及空格，并统一转换为小写进行大小写不敏感匹配
     *
     * @param tableName 原始表名
     * @return 规范化表名
     */
    private static String cleanTableName(String tableName) {
        return tableName.replace("`", "")
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 判断字符串是否全由 ASCII 数字组成
     *
     * @param str 待检测字符串
     * @return true 表示为纯数字
     */
    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
