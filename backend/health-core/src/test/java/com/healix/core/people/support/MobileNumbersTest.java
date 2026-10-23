package com.healix.core.people.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MobileNumbersTest {

    @Test
    void canonicalizesSelfMobile() {
        assertEquals("13800138000", MobileNumbers.trySelf("138-0013-8000"));
        assertEquals("13800138000", MobileNumbers.trySelf("+86 138 0013 8000"));
        assertEquals("13800138000", MobileNumbers.requireSelf("8613800138000"));
    }

    @Test
    void rejectsLandlineAsSelf() {
        assertNull(MobileNumbers.trySelf("010-12345678"));
        assertNull(MobileNumbers.trySelf("12345"));
    }

    @Test
    void nameMatchTrimsEndsOnly() {
        assertTrue(MobileNumbers.sameName(" 王伟 ", "王伟"));
        assertTrue(!MobileNumbers.sameName("王伟", "王偉"));
    }
}
