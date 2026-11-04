package com.healix.core.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** 问卷定义：按顺序引用模块。 */
public record QuestionnaireDef(
        String code,
        int version,
        String title,
        String program,
        String purpose,
        boolean dynamic,
        List<ModuleRef> modules) {

    public record ModuleRef(String code, int version, ShowIf showIf) {
        public ModuleRef(String code, int version) {
            this(code, version, null);
        }
    }

    static QuestionnaireDef parse(JsonNode n) {
        String code = text(n, "code");
        int version = n.path("version").asInt(0);
        String title = text(n, "title");
        String purpose = text(n, "purpose");
        if (code == null || version <= 0 || title == null || purpose == null) {
            throw new IllegalStateException("问卷缺少 code/version/title/purpose");
        }
        List<ModuleRef> modules = new ArrayList<>();
        for (JsonNode m : n.path("modules")) {
            String mc = text(m, "code");
            int mv = m.path("version").asInt(0);
            if (mc == null || mv <= 0) {
                throw new IllegalStateException("问卷模块引用不合法: " + code);
            }
            modules.add(new ModuleRef(mc, mv, ShowIf.parse(m.get("showIf"))));
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("问卷无模块: " + code);
        }
        return new QuestionnaireDef(
                code,
                version,
                title,
                text(n, "program"),
                purpose,
                n.path("dynamic").asBoolean(false),
                List.copyOf(modules));
    }

    public String key() {
        return code + ".v" + version;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
