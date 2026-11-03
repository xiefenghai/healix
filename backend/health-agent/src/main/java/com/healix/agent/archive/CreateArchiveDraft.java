package com.healix.agent.archive;

import java.time.LocalDate;

/** 对话建档抽取草稿（未落库）。 */
public record CreateArchiveDraft(
        String name, String gender, LocalDate birthday, String mobile, boolean noMobile) {

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

    /** 四项齐：姓名、性别、生日、本人手机号（暂无电话本版不走自动建档）。 */
    public boolean isComplete() {
        return hasName() && hasGender() && hasBirthday() && hasMobile() && !noMobile;
    }
}
