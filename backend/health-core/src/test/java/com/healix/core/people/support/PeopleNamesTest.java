package com.healix.core.people.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.healix.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class PeopleNamesTest {

    @Test
    void acceptsChineseAndForeignNames() {
        assertEquals("张三", PeopleNames.requireDisplayName(" 张三 "));
        assertEquals("欧阳修", PeopleNames.requireDisplayName("欧阳修"));
        assertEquals("买买提·艾力", PeopleNames.requireDisplayName("买买提·艾力"));
        assertEquals("Mary Jane", PeopleNames.requireDisplayName("Mary Jane"));
        assertEquals("Jean-Luc", PeopleNames.requireDisplayName("Jean-Luc"));
    }

    @Test
    void rejectsBlankAndTooShort() {
        assertThrows(BusinessException.class, () -> PeopleNames.requireDisplayName(""));
        assertThrows(BusinessException.class, () -> PeopleNames.requireDisplayName("  "));
        assertThrows(BusinessException.class, () -> PeopleNames.requireDisplayName("王"));
        assertEquals("请填写姓名", thrownMessage(""));
        assertEquals("姓名至少2个字", thrownMessage("王"));
    }

    @Test
    void rejectsDigitsAndIllegalChars() {
        assertEquals("姓名格式不正确", thrownMessage("123"));
        assertEquals("姓名格式不正确", thrownMessage("12345"));
        assertEquals("姓名格式不正确", thrownMessage("张三1"));
        assertEquals("姓名格式不正确", thrownMessage("张三@"));
        assertEquals("姓名格式不正确", thrownMessage("· ·"));
    }

    private static String thrownMessage(String raw) {
        BusinessException ex =
                assertThrows(BusinessException.class, () -> PeopleNames.requireDisplayName(raw));
        return ex.getMessage();
    }
}
