package com.healix.web.b.persona;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.persona.domain.PeoplePersona;
import org.junit.jupiter.api.Test;

class PersonaAppServiceFingerprintTest {

    @Test
    void fingerprintMatchesCachedNarrative_hitWhenSame() {
        PeoplePersona row = new PeoplePersona();
        row.setNarrativeJson("{\"headline\":\"h\",\"narrative\":\"n\"}");
        row.setNarrativeFingerprint("sha256:abc");

        ObjectNode dims = JsonUtils.emptyObject();
        dims.put("sourceFingerprint", "sha256:abc");

        assertTrue(PersonaAppService.fingerprintMatchesCachedNarrative(row, dims));
    }

    @Test
    void fingerprintMatchesCachedNarrative_missWhenStaleOrEmpty() {
        PeoplePersona row = new PeoplePersona();
        row.setNarrativeJson("{\"headline\":\"h\",\"narrative\":\"n\"}");
        row.setNarrativeFingerprint("sha256:old");

        ObjectNode dims = JsonUtils.emptyObject();
        dims.put("sourceFingerprint", "sha256:new");
        assertFalse(PersonaAppService.fingerprintMatchesCachedNarrative(row, dims));

        assertFalse(PersonaAppService.fingerprintMatchesCachedNarrative(null, dims));
        assertFalse(PersonaAppService.fingerprintMatchesCachedNarrative(row, null));

        row.setNarrativeJson(null);
        dims.put("sourceFingerprint", "sha256:old");
        assertFalse(PersonaAppService.fingerprintMatchesCachedNarrative(row, dims));
    }
}
