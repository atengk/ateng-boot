package io.github.atengk.boot.mail.template.renderer;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 邮件模板类路径资源与国际化级联路径解析工具
 * <p>
 * 统一收敛邮件模板引擎（如 Thymeleaf、Simple、Freemarker 等）在类路径下的
 * 常用目录寻址前缀、扩展名提取及 {@link Locale} 多语言级联候选名称生成算法。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public final class MailTemplateResourceUtil {

    /**
     * 标准邮件模板在类路径下的探测目录前缀清单
     */
    private static final String[] PROBE_PREFIXES = new String[]{
            "",
            "templates/",
            "templates/mail/",
            "mail/"
    };

    private MailTemplateResourceUtil() {
    }

    /**
     * 根据指定的模板名称与目标 {@link Locale}，生成多语言级联逻辑候选名称列表
     * <p>
     * 保持与输入 templateName 的扩展名形态对称：
     * 若输入带扩展名（如 "welcome.html"），生成的候选也带扩展名（如 "welcome_en_US.html"）；
     * 若输入不带扩展名（如 "welcome"），生成的候选同样保持不带扩展名（如 "welcome_en_US"）。
     * </p>
     *
     * @param templateName 基础模板名称（如 "welcome" 或 "welcome.html"）
     * @param locale       目标区域语言标识
     * @return 有序的多语言级联候选逻辑名称列表
     */
    public static List<String> buildLocaleCandidateNames(String templateName, Locale locale) {
        if (templateName == null || templateName.isBlank()) {
            return Collections.emptyList();
        }

        String base = templateName;
        String ext = "";
        int dotIndex = templateName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < templateName.length() - 1) {
            String potentialExt = templateName.substring(dotIndex);
            if (".html".equalsIgnoreCase(potentialExt)
                    || ".htm".equalsIgnoreCase(potentialExt)
                    || ".ftl".equalsIgnoreCase(potentialExt)
                    || ".ftlh".equalsIgnoreCase(potentialExt)) {
                base = templateName.substring(0, dotIndex);
                ext = potentialExt;
            }
        }

        List<String> candidates = new ArrayList<>();
        if (locale != null) {
            String lang = locale.getLanguage();
            String country = locale.getCountry();
            if (lang != null && !lang.isBlank() && country != null && !country.isBlank()) {
                candidates.add(base + "_" + lang + "_" + country + ext);
            }
            if (lang != null && !lang.isBlank()) {
                candidates.add(base + "_" + lang + ext);
            }
        }

        if (!candidates.contains(templateName)) {
            candidates.add(templateName);
        }

        return Collections.unmodifiableList(candidates);
    }

    /**
     * 根据指定的模板名称与目标 {@link Locale}，生成用于物理文件查找的候选文件名列表（强制带有合法文件扩展名）
     *
     * @param templateName 基础模板名称（如 "welcome" 或 "welcome.html"）
     * @param locale       目标区域语言标识
     * @param defaultExt   缺失扩展名时的默认扩展名（如 ".html"）
     * @return 有序的物理模板文件名候选列表
     */
    public static List<String> buildPhysicalCandidateFileNames(String templateName, Locale locale, String defaultExt) {
        if (templateName == null || templateName.isBlank()) {
            return Collections.emptyList();
        }

        String effectiveExt = defaultExt != null ? defaultExt : ".html";
        String base = templateName;
        int dotIndex = templateName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < templateName.length() - 1) {
            String potentialExt = templateName.substring(dotIndex);
            if (".html".equalsIgnoreCase(potentialExt)
                    || ".htm".equalsIgnoreCase(potentialExt)
                    || ".ftl".equalsIgnoreCase(potentialExt)
                    || ".ftlh".equalsIgnoreCase(potentialExt)) {
                base = templateName.substring(0, dotIndex);
                effectiveExt = potentialExt;
            }
        }

        List<String> candidates = new ArrayList<>();
        if (locale != null) {
            String lang = locale.getLanguage();
            String country = locale.getCountry();
            if (lang != null && !lang.isBlank() && country != null && !country.isBlank()) {
                candidates.add(base + "_" + lang + "_" + country + effectiveExt);
            }
            if (lang != null && !lang.isBlank()) {
                candidates.add(base + "_" + lang + effectiveExt);
            }
        }

        String baseWithExt = base + effectiveExt;
        if (!candidates.contains(baseWithExt)) {
            candidates.add(baseWithExt);
        }
        if (!candidates.contains(templateName)) {
            candidates.add(templateName);
        }

        return Collections.unmodifiableList(candidates);
    }

    /**
     * 在标准类路径探测目录下查找指定候选模板资源（自动适配补全默认 .html 后缀）
     *
     * @param candidateName 候选模板文件名（如 "welcome_en.html" 或 "welcome_en"）
     * @return 存在的 {@link Resource} 对象，若所有探测路径均不存在则返回 null
     */
    public static Resource resolveClasspathResource(String candidateName) {
        if (candidateName == null || candidateName.isBlank()) {
            return null;
        }

        String path = candidateName.endsWith(".html") || candidateName.endsWith(".htm") || candidateName.endsWith(".ftl")
                ? candidateName
                : candidateName + ".html";

        for (String prefix : PROBE_PREFIXES) {
            String fullPath = prefix.isEmpty() ? path : prefix + path;
            Resource resource = new ClassPathResource(fullPath);
            if (resource.exists()) {
                return resource;
            }
        }
        return null;
    }

    /**
     * 检查指定候选模板名称在类路径标准目录下是否存在
     *
     * @param candidateName 候选模板文件名
     * @return true 表示存在，false 表示不存在
     */
    public static boolean existsInClasspath(String candidateName) {
        return resolveClasspathResource(candidateName) != null;
    }

    /**
     * 根据模板名称与目标 {@link Locale}，解析在类路径下真实存在的最优逻辑模板名称（供 Thymeleaf 等模板引擎使用）
     *
     * @param templateName 基础模板名称（如 "welcome"）
     * @param locale       目标区域语言标识
     * @return 真实存在的最优先逻辑候选名称（如存在 welcome_en.html 且输入 welcome 时返回 "welcome_en"）；
     *         若均未找到物理文件则保底返回原始 templateName
     */
    public static String resolveExistingTemplateName(String templateName, Locale locale) {
        if (templateName == null || templateName.isBlank() || locale == null) {
            return templateName;
        }
        List<String> candidates = buildLocaleCandidateNames(templateName, locale);
        for (String candidate : candidates) {
            if (existsInClasspath(candidate)) {
                return candidate;
            }
        }
        return templateName;
    }
}
