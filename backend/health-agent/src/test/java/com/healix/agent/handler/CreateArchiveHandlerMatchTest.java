package com.healix.agent.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.agent.archive.CreateArchiveDraft;
import com.healix.agent.archive.CreateArchiveParser;
import org.junit.jupiter.api.Test;

class CreateArchiveHandlerMatchTest {

    @Test
    void freshStartWhenNoIdentityThisTurn() {
        assertTrue(CreateArchiveHandler.isFreshArchiveStart("帮我建档", CreateArchiveParser.parse("帮我建档")));
        assertTrue(CreateArchiveHandler.isFreshArchiveStart("随便说一句", CreateArchiveParser.parse("随便说一句")));
    }

    @Test
    void notFreshWhenIdentityPresent() {
        CreateArchiveDraft withName = CreateArchiveParser.parse("李敏 女");
        assertFalse(CreateArchiveHandler.isFreshArchiveStart("李敏 女", withName));
        CreateArchiveDraft withMobile = CreateArchiveParser.parse("13800138000");
        assertFalse(CreateArchiveHandler.isFreshArchiveStart("13800138000", withMobile));
    }
}
