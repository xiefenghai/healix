package com.healix.core.people.support;

import com.healix.common.exception.BusinessException;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 患者展示名校验：须为真实人名，拒绝纯数字等脏输入。 */
public final class PeopleNames {

    private static final int MIN_LEN = 2;
    private static final int MAX_LEN = 64;
    /** 中文、英文、少数民族间隔号、英文复合名连字符/空格。 */
    private static final Pattern ALLOWED_CHARS =
            Pattern.compile("^[\\u4e00-\\u9fffA-Za-z·•\\-\\s]+$");
    private static final Pattern HAS_LETTER = Pattern.compile("[\\u4e00-\\u9fffA-Za-z]");

    private PeopleNames() {}

    /**
     * 校验并返回 trim 后的姓名。
     *
     * @throws BusinessException 为空、过短/过长、含数字或非法字符时
     */
    public static String requireDisplayName(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(400, "请填写姓名");
        }
        String name = raw.trim();
        if (name.length() < MIN_LEN) {
            throw new BusinessException(400, "姓名至少2个字");
        }
        if (name.length() > MAX_LEN) {
            throw new BusinessException(400, "姓名过长");
        }
        if (!ALLOWED_CHARS.matcher(name).matches() || !HAS_LETTER.matcher(name).find()) {
            throw new BusinessException(400, "姓名格式不正确");
        }
        // 「· ·」「- -」这类去符号后不足 2 个字的也不算人名
        String lettersOnly = name.replaceAll("[·•\\-\\s]", "");
        if (lettersOnly.length() < MIN_LEN) {
            throw new BusinessException(400, "姓名格式不正确");
        }
        return name;
    }
}
