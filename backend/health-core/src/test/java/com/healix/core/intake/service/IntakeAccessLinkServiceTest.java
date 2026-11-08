package com.healix.core.intake.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class IntakeAccessLinkServiceTest {

    @Test
    void politeAddress_byGender_safeHonorificOnly() {
        LocalDate young = LocalDate.now().minusYears(30);
        LocalDate mid = LocalDate.now().minusYears(52);
        LocalDate elder = LocalDate.now().minusYears(70);

        assertEquals("贺先生", IntakeAccessLinkService.politeAddress("贺桐生", "MALE", young));
        // 不再按年龄叫叔叔/伯伯，避免误判尴尬
        assertEquals("贺先生", IntakeAccessLinkService.politeAddress("贺桐生", "男", mid));
        assertEquals("贺先生", IntakeAccessLinkService.politeAddress("贺桐生", "MALE", elder));
        assertEquals("王女士", IntakeAccessLinkService.politeAddress("王芳", "FEMALE", young));
        assertEquals("王女士", IntakeAccessLinkService.politeAddress("王芳", "女", mid));
        assertEquals("欧阳女士", IntakeAccessLinkService.politeAddress("欧阳娜娜", "FEMALE", young));
        assertEquals("欧阳先生", IntakeAccessLinkService.politeAddress("欧阳修", "MALE", young));
        assertEquals("朋友", IntakeAccessLinkService.politeAddress("  ", "MALE", young));
    }

    @Test
    void politeAddress_nonPersonName_usesFullName() {
        assertEquals("分层评估", IntakeAccessLinkService.politeAddress("分层评估", "MALE", null));
        assertEquals("全流程测试", IntakeAccessLinkService.politeAddress("全流程测试", "男", null));
        assertEquals("问卷样例", IntakeAccessLinkService.politeAddress("问卷样例", "FEMALE", null));
    }

    @Test
    void politeAddress_unknownGender_usesFullName() {
        assertEquals("贺桐生", IntakeAccessLinkService.politeAddress("贺桐生", null, null));
        assertEquals("贺桐生", IntakeAccessLinkService.politeAddress("贺桐生", "", null));
    }

    @Test
    void pickHonorific_unknownGenderEmpty() {
        assertEquals("", IntakeAccessLinkService.pickHonorific(null));
        assertEquals("", IntakeAccessLinkService.pickHonorific(""));
        assertEquals("先生", IntakeAccessLinkService.pickHonorific("MALE"));
        assertEquals("女士", IntakeAccessLinkService.pickHonorific("女"));
    }

    @Test
    void looksLikePersonName_filtersTestLabels() {
        assertTrue(IntakeAccessLinkService.looksLikePersonName("贺桐生"));
        assertTrue(IntakeAccessLinkService.looksLikePersonName("欧阳娜娜"));
        assertFalse(IntakeAccessLinkService.looksLikePersonName("分层评估"));
        assertFalse(IntakeAccessLinkService.looksLikePersonName("全流程测试"));
        assertFalse(IntakeAccessLinkService.looksLikePersonName("demoUser"));
    }

    @Test
    void buildCopyText_includesStaffOrgBenefitAndValidity() {
        String text = IntakeAccessLinkService.buildCopyText(
                "孙悦", "周先生", "https://healix.example/i/abc", 7);
        assertFalse(text.contains("【"));
        assertTrue(text.startsWith("周先生您好，我是您的专属健管师孙悦。"));
        assertTrue(text.contains("健康管理方案"));
        assertTrue(text.contains("7天内有效"));
        assertTrue(text.contains("👉 点击填写：https://healix.example/i/abc"));
        assertFalse(text.contains("初步评估"));
    }
}
