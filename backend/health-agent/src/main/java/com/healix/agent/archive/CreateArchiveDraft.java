package com.healix.agent.archive;

import java.time.LocalDate;

/** 对话建档抽取草稿（未落库）。身高选填，便于后续 BMI/热量。 */
public record CreateArchiveDraft(
        String name,
        String gender,
        LocalDate birthday,
        String mobile,
        boolean noMobile,
        Integer heightCm) {

    public CreateArchiveDraft(String name, String gender, LocalDate birthday, String mobile, boolean noMobile) {
        this(name, gender, birthday, mobile, noMobile, null);
    }

    public boolean hasName() {
        return name != null && !name.isBlank();
    }

    public boolean hasGender() {
        return "MALE".equals(gender) || "FEMALE".equals(gender);
    }

    public boolean hasBirthday() {
        return birthday != null;
    }

    public boolean hasMobile() {
        return mobile != null && !mobile.isBlank();
    }

    public boolean hasHeightCm() {
        return heightCm != null && heightCm >= 100 && heightCm <= 230;
    }

    /** 四项齐：姓名、性别、生日、本人手机号（暂无电话本版不走自动建档）。身高不阻断完成。 */
    public boolean isComplete() {
        return hasName() && hasGender() && hasBirthday() && hasMobile() && !noMobile;
    }
}
