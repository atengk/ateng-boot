package io.github.atengk.boot.web.xss;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XSS 安全清洗 HTTP 请求包装器
 * 拦截 Query 参数与 Form 表单参数并实施双轨无害化清洗
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final XssCleaner cleaner;

    public XssHttpServletRequestWrapper(HttpServletRequest request, XssCleaner cleaner) {
        super(request);
        this.cleaner = cleaner != null ? cleaner : new XssCleaner();
    }

    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        return cleaner.clean(value);
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) {
            return null;
        }
        String[] cleaned = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            cleaned[i] = cleaner.clean(values[i]);
        }
        return cleaned;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> original = super.getParameterMap();
        if (original == null || original.isEmpty()) {
            return original;
        }
        Map<String, String[]> cleaned = new LinkedHashMap<>(original.size());
        for (Map.Entry<String, String[]> entry : original.entrySet()) {
            String[] values = entry.getValue();
            if (values == null) {
                cleaned.put(entry.getKey(), null);
            } else {
                String[] cleanedValues = new String[values.length];
                for (int i = 0; i < values.length; i++) {
                    cleanedValues[i] = cleaner.clean(values[i]);
                }
                cleaned.put(entry.getKey(), cleanedValues);
            }
        }
        return Collections.unmodifiableMap(cleaned);
    }

    public XssCleaner getCleaner() {
        return cleaner;
    }
}
