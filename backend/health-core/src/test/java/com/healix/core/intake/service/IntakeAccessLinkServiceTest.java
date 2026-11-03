package com.healix.core.intake.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class IntakeAccessLinkServiceTest {

    @Test
    void politeAddress_byGenderAndAge() {
        LocalDate young = LocalDate.now().minusYears(30);
        LocalDate mid = LocalDate.now().minusYears(52);
        LocalDate elder = LocalDate.now().minusYears(70);

        assertEquals("贺先生", IntakeAccessLinkService.politeAddress("贺桐生", "MALE", young));
        assertEquals("贺叔叔", IntakeAccessLinkService.politeAddress("贺桐生", "男", mid));
        assertEquals("贺伯伯", IntakeAccessLinkService.politeAddress("贺桐生", "MALE", elder));
        assertEquals("王女士", IntakeAccessLinkService.politeAddress("王芳", "FEMALE", young));
        assertEquals("王阿姨", IntakeAccessLinkService.politeAddress("王芳", "女", mid));
        assertEquals("欧阳先生", IntakeAccessLinkService.politeAddress("欧阳修", "MALE", young));
        assertEquals("患者", IntakeAccessLinkService.politeAddress("  ", "MALE", young));
    }

    @Test
    void pickHonorific_unknownGenderDefaults() {
        assertEquals("先生", IntakeAccessLinkService.pickHonorific(null, 30));
        assertEquals("叔叔", IntakeAccessLinkService.pickHonorific("", 60));
    }
}
