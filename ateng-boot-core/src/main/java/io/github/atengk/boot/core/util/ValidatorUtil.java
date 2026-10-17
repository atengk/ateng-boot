package io.github.atengk.boot.core.util;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 常用业务数据格式与字段合法性校验工具类
 * 基于纯 JDK 17 原生正则与算法实现，提供手机号、身份证（带加权模11校验码）、邮箱、IP、税号等高频编程式断言门面
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class ValidatorUtil {

    /**
     * 中国大陆 11 位手机号码正则 (13x ~ 19x)
     */
    public static final Pattern PATTERN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 中国大陆固定电话正则 (支持 3~4 位区号，7~8 位电话号码，带或不带短横线)
     */
    public static final Pattern PATTERN_TEL = Pattern.compile("^(?:0\\d{2,3}-?)?\\d{7,8}$");

    /**
     * 规范电子邮箱正则
     */
    public static final Pattern PATTERN_EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    /**
     * IPv4 正则 (0.0.0.0 ~ 255.255.255.255)
     */
    public static final Pattern PATTERN_IPV4 = Pattern.compile("^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)$");

    /**
     * IPv6 标准格式正则
     */
    public static final Pattern PATTERN_IPV6 = Pattern.compile("^(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}$|^(?:[0-9a-fA-F]{1,4}:){1,7}:$|^(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}$");

    /**
     * 中国车牌号正则 (含新能源车牌、民用、挂、学、警车牌)
     */
    public static final Pattern PATTERN_CAR_LICENSE = Pattern.compile("^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳]$");

    /**
     * 纯中文字符正则
     */
    public static final Pattern PATTERN_CHINESE = Pattern.compile("^[\\u4e00-\\u9fa5]+$");

    /**
     * 统一社会信用代码正则 (18 位，GB 32100-2015，不含 I, O, Z, S, V)
     */
    public static final Pattern PATTERN_SOCIAL_CREDIT_CODE = Pattern.compile("^[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}$");

    /**
     * HTTP / HTTPS 标准 URL 正则
     */
    public static final Pattern PATTERN_URL = Pattern.compile("^https?://[^\\s/$.?#].[^\\s]*$", Pattern.CASE_INSENSITIVE);

    /**
     * 18 位身份证前 17 位加权因子
     */
    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /**
     * 18 位身份证模 11 余数对应的校验码字符 (余数 0~10)
     */
    private static final char[] ID_CARD_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private ValidatorUtil() {
    }

    /**
     * 判断字符串是否匹配指定正则表达式
     *
     * @param pattern 预编译正则表达式
     * @param value   待校验字符序列
     * @return true 若匹配成功；若 pattern 或 value 为 null 则返回 false
     */
    public static boolean isMatch(Pattern pattern, CharSequence value) {
        if (pattern == null || value == null) {
            return false;
        }
        return pattern.matcher(value).matches();
    }

    /**
     * 判断是否为合法的中国大陆 11 位手机号码
     *
     * @param value 待校验字符序列
     * @return true 若符合手机号规则
     */
    public static boolean isMobile(CharSequence value) {
        return isMatch(PATTERN_MOBILE, value);
    }

    /**
     * 判断是否为合法的中国大陆固定电话
     *
     * @param value 待校验字符序列
     * @return true 若符合固定电话规则
     */
    public static boolean isTel(CharSequence value) {
        return isMatch(PATTERN_TEL, value);
    }

    /**
     * 判断是否为电话号码（手机号或固话）
     *
     * @param value 待校验字符序列
     * @return true 若符合手机号或固定电话规则
     */
    public static boolean isPhone(CharSequence value) {
        return isMobile(value) || isTel(value);
    }

    /**
     * 判断是否为合法的电子邮箱地址
     *
     * @param value 待校验字符序列
     * @return true 若符合电子邮箱规则
     */
    public static boolean isEmail(CharSequence value) {
        return isMatch(PATTERN_EMAIL, value);
    }

    /**
     * 判断是否为合法的 IPv4 地址
     *
     * @param value 待校验字符序列
     * @return true 若符合 IPv4 规范
     */
    public static boolean isIpv4(CharSequence value) {
        return isMatch(PATTERN_IPV4, value);
    }

    /**
     * 判断是否为合法的 IPv6 地址
     *
     * @param value 待校验字符序列
     * @return true 若符合 IPv6 规范
     */
    public static boolean isIpv6(CharSequence value) {
        return isMatch(PATTERN_IPV6, value);
    }

    /**
     * 判断是否为合法的 IP 地址（IPv4 或 IPv6）
     *
     * @param value 待校验字符序列
     * @return true 若为合法 IP 地址
     */
    public static boolean isIp(CharSequence value) {
        return isIpv4(value) || isIpv6(value);
    }

    /**
     * 判断是否为中国车牌号码（含新能源车牌）
     *
     * @param value 待校验字符序列
     * @return true 若符合车牌规则
     */
    public static boolean isCarLicense(CharSequence value) {
        return isMatch(PATTERN_CAR_LICENSE, value);
    }

    /**
     * 判断字符串是否全部由纯中文字符组成
     *
     * @param value 待校验字符序列
     * @return true 若全部为中文字符
     */
    public static boolean isChinese(CharSequence value) {
        return isMatch(PATTERN_CHINESE, value);
    }

    /**
     * 判断是否为合法的 18 位统一社会信用代码
     *
     * @param value 待校验字符序列
     * @return true 若符合统信代码规则
     */
    public static boolean isSocialCreditCode(CharSequence value) {
        return isMatch(PATTERN_SOCIAL_CREDIT_CODE, value);
    }

    /**
     * 判断是否为以 http:// 或 https:// 开头的合法网络 URL
     *
     * @param value 待校验字符序列
     * @return true 若符合 URL 规则
     */
    public static boolean isUrl(CharSequence value) {
        return isMatch(PATTERN_URL, value);
    }

    /**
     * 高精度校验中国居民身份证号码合法性
     * 支持 18 位身份证权威加权模 11 算法 (ISO 7064:1983.MOD 11-2) 与出生日期真实性校验，兼容 15 位老身份证
     *
     * @param idCard 待校验身份证号码
     * @return true 若通过完整校验
     */
    public static boolean isIdCard(CharSequence idCard) {
        if (idCard == null) {
            return false;
        }
        String str = idCard.toString().trim();
        int len = str.length();

        if (len == 18) {
            return validate18IdCard(str);
        } else if (len == 15) {
            return validate15IdCard(str);
        }
        return false;
    }

    private static boolean validate18IdCard(String idCard) {
        // 1. 前 17 位必须全为数字，末位可为数字或 X/x
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(idCard.charAt(i))) {
                return false;
            }
        }
        char lastChar = Character.toUpperCase(idCard.charAt(17));
        if (!Character.isDigit(lastChar) && lastChar != 'X') {
            return false;
        }

        // 2. 出生日期真实性验证 (yyyyMMdd)
        try {
            int year = Integer.parseInt(idCard.substring(6, 10));
            int month = Integer.parseInt(idCard.substring(10, 12));
            int day = Integer.parseInt(idCard.substring(12, 14));

            int currentYear = LocalDate.now().getYear();
            if (year < 1900 || year > currentYear) {
                return false;
            }
            LocalDate birthDate = LocalDate.of(year, month, day);
            if (birthDate.isAfter(LocalDate.now())) {
                return false;
            }
        } catch (Exception ex) {
            return false;
        }

        // 3. ISO 7064:1983.MOD 11-2 加权模 11 校验码算法
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idCard.charAt(i) - '0') * ID_CARD_WEIGHTS[i];
        }
        int remainder = sum % 11;
        char expectedCheckCode = ID_CARD_CHECK_CODES[remainder];

        return lastChar == expectedCheckCode;
    }

    private static boolean validate15IdCard(String idCard) {
        for (int i = 0; i < 15; i++) {
            if (!Character.isDigit(idCard.charAt(i))) {
                return false;
            }
        }
        try {
            int year = 1900 + Integer.parseInt(idCard.substring(6, 8));
            int month = Integer.parseInt(idCard.substring(8, 10));
            int day = Integer.parseInt(idCard.substring(10, 12));
            LocalDate.of(year, month, day);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static volatile jakarta.validation.Validator defaultValidator;

    /**
     * 获取受管或默认的 Jakarta Validator 实例
     * 优先从 Spring 上下文中获取 Spring 受管 Validator；若未在 Spring 容器环境中，则通过标准单例工厂平滑回退构建
     *
     * @return Validator 校验器实例
     */
    public static jakarta.validation.Validator getValidator() {
        try {
            jakarta.validation.Validator springValidator = SpringUtil.getBean(jakarta.validation.Validator.class);
            if (springValidator != null) {
                return springValidator;
            }
        } catch (Throwable ignored) {
            // SpringUtil 未初始化或容器中无 Validator，回退至单例工厂
        }
        if (defaultValidator == null) {
            synchronized (ValidatorUtil.class) {
                if (defaultValidator == null) {
                    try {
                        jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
                        defaultValidator = factory.getValidator();
                    } catch (Throwable ex) {
                        // 若类路径缺失 jakarta.el 依赖，尝试通过 Hibernate Validator 的 ParameterMessageInterpolator 无 EL 降级构建
                        try {
                            jakarta.validation.Configuration<?> configuration = jakarta.validation.Validation.byDefaultProvider().configure();
                            Class<?> interpolatorClass = Class.forName("org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator");
                            jakarta.validation.MessageInterpolator interpolator = (jakarta.validation.MessageInterpolator) interpolatorClass.getDeclaredConstructor().newInstance();
                            configuration.messageInterpolator(interpolator);
                            defaultValidator = configuration.buildValidatorFactory().getValidator();
                        } catch (Throwable fallbackEx) {
                            throw ex;
                        }
                    }
                }
            }
        }
        return defaultValidator;
    }

    /**
     * 编程式整对象 JSR-303 校验并执行快速失败断言
     * 若对象校验不通过，直接截取第一条违规信息抛出 BusinessException(400, message)
     *
     * @param <T>    待校验对象类型
     * @param object 待校验对象实体
     * @param groups 可选校验分组
     * @throws io.github.atengk.boot.core.exception.BusinessException 当对象为 null 或存在字段违规时抛出
     */
    public static <T> void validate(T object, Class<?>... groups) {
        if (object == null) {
            throw new io.github.atengk.boot.core.exception.BusinessException(
                    io.github.atengk.boot.core.code.CommonResultCode.BAD_REQUEST.getCode(),
                    "待校验对象不能为 null"
            );
        }
        java.util.Set<jakarta.validation.ConstraintViolation<T>> violations = validateResult(object, groups);
        if (!violations.isEmpty()) {
            String message = violations.iterator().next().getMessage();
            throw new io.github.atengk.boot.core.exception.BusinessException(
                    io.github.atengk.boot.core.code.CommonResultCode.BAD_REQUEST.getCode(),
                    message
            );
        }
    }

    /**
     * 编程式整对象 JSR-303 校验并返回全部违规集合
     *
     * @param <T>    待校验对象类型
     * @param object 待校验对象实体
     * @param groups 可选校验分组
     * @return 违规信息集合，若无违规则返回空集合
     */
    public static <T> java.util.Set<jakarta.validation.ConstraintViolation<T>> validateResult(T object, Class<?>... groups) {
        if (object == null) {
            return java.util.Collections.emptySet();
        }
        jakarta.validation.Validator validator = getValidator();
        if (validator == null) {
            return java.util.Collections.emptySet();
        }
        return validator.validate(object, groups);
    }

    /**
     * 编程式单属性 JSR-303 校验并执行快速失败断言
     *
     * @param <T>          待校验对象类型
     * @param object       待校验对象实体
     * @param propertyName 待校验属性名
     * @param groups       可选校验分组
     * @throws io.github.atengk.boot.core.exception.BusinessException 当对象为 null 或属性违规时抛出
     */
    public static <T> void validateProperty(T object, String propertyName, Class<?>... groups) {
        if (object == null) {
            throw new io.github.atengk.boot.core.exception.BusinessException(
                    io.github.atengk.boot.core.code.CommonResultCode.BAD_REQUEST.getCode(),
                    "待校验对象不能为 null"
            );
        }
        jakarta.validation.Validator validator = getValidator();
        if (validator == null) {
            return;
        }
        java.util.Set<jakarta.validation.ConstraintViolation<T>> violations = validator.validateProperty(object, propertyName, groups);
        if (!violations.isEmpty()) {
            String message = violations.iterator().next().getMessage();
            throw new io.github.atengk.boot.core.exception.BusinessException(
                    io.github.atengk.boot.core.code.CommonResultCode.BAD_REQUEST.getCode(),
                    message
            );
        }
    }
}
