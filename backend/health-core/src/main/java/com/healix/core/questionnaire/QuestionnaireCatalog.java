package com.healix.core.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.FieldDef;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 问卷 / 模块目录：启动时从 classpath {@code questionnaires/} 下的 JSON 载入并校验。
 *
 * <ul>
 *   <li>引用的字段必须在 {@link FieldCatalog}
 *   <li>显示条件引用的字段必须出现在同一问卷内
 *   <li>同一 code + version 不得重复
 * </ul>
 */
@Slf4j
@Component
public class QuestionnaireCatalog {

    private final Map<String, ModuleDef> modules;
    private final Map<String, QuestionnaireDef> questionnaires;

    @Autowired
    public QuestionnaireCatalog(FieldCatalog fields) {
        this(loadAll("classpath*:questionnaires/modules/*.json", ModuleDef::parse),
                loadAll("classpath*:questionnaires/questionnaires/*.json", QuestionnaireDef::parse),
                fields);
    }

    QuestionnaireCatalog(
            Map<String, ModuleDef> modules,
            Map<String, QuestionnaireDef> questionnaires,
            FieldCatalog fields) {
        this.modules = Map.copyOf(modules);
        this.questionnaires = Map.copyOf(questionnaires);
        validate(fields);
        log.info(
                "Questionnaire catalog loaded: {} modules, {} questionnaires",
                this.modules.size(),
                this.questionnaires.size());
    }

    public Optional<ModuleDef> module(String code, int version) {
        return Optional.ofNullable(modules.get(code + ".v" + version));
    }

    public ModuleDef requireModule(String code, int version) {
        return module(code, version)
                .orElseThrow(() -> new IllegalArgumentException("未知模块: " + code + ".v" + version));
    }

    public Optional<QuestionnaireDef> questionnaire(String code, int version) {
        return Optional.ofNullable(questionnaires.get(code + ".v" + version));
    }

    public QuestionnaireDef requireQuestionnaire(String code, int version) {
        return questionnaire(code, version)
                .orElseThrow(() -> new IllegalArgumentException("未知问卷: " + code + ".v" + version));
    }

    /** 取某问卷 code 的最高版本。 */
    public QuestionnaireDef latest(String code) {
        return questionnaires.values().stream()
                .filter(q -> q.code().equals(code))
                .max(java.util.Comparator.comparingInt(QuestionnaireDef::version))
                .orElseThrow(() -> new IllegalArgumentException("未知问卷: " + code));
    }

    public Collection<ModuleDef> allModules() {
        return modules.values();
    }

    public Collection<QuestionnaireDef> allQuestionnaires() {
        return questionnaires.values();
    }

    /** 展开问卷全部题目（按模块顺序）；动态模块返回空 items。 */
    public List<ModuleItem> itemsOf(QuestionnaireDef q) {
        return itemsOf(q, null);
    }

    /**
     * 展开问卷题目；若提供 answers lookup，则跳过模块级 showIf 不匹配的模块。
     */
    public List<ModuleItem> itemsOf(
            QuestionnaireDef q, java.util.function.Function<String, com.fasterxml.jackson.databind.JsonNode> lookup) {
        List<ModuleItem> out = new ArrayList<>();
        for (QuestionnaireDef.ModuleRef ref : q.modules()) {
            if (lookup != null && ref.showIf() != null && !ref.showIf().matches(lookup)) {
                continue;
            }
            ModuleDef m = requireModule(ref.code(), ref.version());
            out.addAll(m.items());
        }
        return out;
    }

    /**
     * 字段在当前答案下是否可见。
     * 只出现在未命中 showIf 的模块 / 题目里时为 false；问卷外的键保持 true，避免误删非题目字段。
     */
    public boolean fieldVisible(
            QuestionnaireDef q, String field, java.util.function.Function<String, JsonNode> lookup) {
        boolean seen = false;
        boolean visible = false;
        for (QuestionnaireDef.ModuleRef ref : q.modules()) {
            boolean moduleHidden = ref.showIf() != null && !ref.showIf().matches(lookup);
            ModuleDef m = requireModule(ref.code(), ref.version());
            for (ModuleItem item : m.items()) {
                if (!field.equals(item.field())) {
                    continue;
                }
                seen = true;
                if (moduleHidden) {
                    continue;
                }
                if (item.showIf() == null || item.showIf().matches(lookup)) {
                    visible = true;
                }
            }
        }
        return !seen || visible;
    }

    /** 去掉当前 showIf 未命中的题目答案。草稿不调用，只在提交 / 给 AI 的事实里清洗。 */
    public void dropHiddenAnswers(QuestionnaireDef q, ObjectNode answers) {
        if (answers == null) {
            return;
        }
        java.util.function.Function<String, JsonNode> lookup = answers::get;
        List<String> drop = new ArrayList<>();
        answers.fieldNames().forEachRemaining(code -> {
            if (!fieldVisible(q, code, lookup)) {
                drop.add(code);
            }
        });
        drop.forEach(answers::remove);
    }

    private void validate(FieldCatalog fields) {
        for (ModuleDef m : modules.values()) {
            Set<String> seen = new LinkedHashSet<>();
            for (ModuleItem it : m.items()) {
                if (!seen.add(it.field())) {
                    throw new IllegalStateException("模块内字段重复: " + m.key() + " / " + it.field());
                }
                Optional<FieldDef> def = fields.find(it.field());
                if (def.isEmpty()) {
                    throw new IllegalStateException("模块引用未知字段: " + m.key() + " / " + it.field());
                }
                if (def.get().readOnly() && !def.get().virtual()) {
                    // 只读字段（如 lab）不应出题；metric 可出题写回指标表
                    if (def.get().store().name().equals("LAB")) {
                        throw new IllegalStateException("只读检验字段不可出题: " + it.field());
                    }
                }
            }
        }
        for (QuestionnaireDef q : questionnaires.values()) {
            Set<String> inQuestionnaire = new LinkedHashSet<>();
            for (QuestionnaireDef.ModuleRef ref : q.modules()) {
                ModuleDef m = modules.get(ref.code() + ".v" + ref.version());
                if (m == null) {
                    throw new IllegalStateException(
                            "问卷引用未知模块: " + q.key() + " → " + ref.code() + ".v" + ref.version());
                }
                for (ModuleItem it : m.items()) {
                    inQuestionnaire.add(it.field());
                }
            }
            for (QuestionnaireDef.ModuleRef ref : q.modules()) {
                if (ref.showIf() != null) {
                    validateShowIfFields(
                            ref.showIf(), inQuestionnaire, q.key(), ref.code() + ".v" + ref.version() + "#module");
                }
                ModuleDef m = requireModule(ref.code(), ref.version());
                for (ModuleItem it : m.items()) {
                    if (it.showIf() != null) {
                        validateShowIfFields(it.showIf(), inQuestionnaire, q.key(), it.field());
                    }
                }
            }
        }
    }

    private static void validateShowIfFields(
            ShowIf showIf, Set<String> inQuestionnaire, String qKey, String itemField) {
        if (showIf.all() != null) {
            showIf.all().forEach(s -> validateShowIfFields(s, inQuestionnaire, qKey, itemField));
            return;
        }
        if (showIf.any() != null) {
            showIf.any().forEach(s -> validateShowIfFields(s, inQuestionnaire, qKey, itemField));
            return;
        }
        if (showIf.field() != null && !inQuestionnaire.contains(showIf.field())) {
            throw new IllegalStateException(
                    "显示条件引用的字段不在同一问卷内: " + qKey + " / " + itemField + " → " + showIf.field());
        }
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(JsonNode n);
    }

    private static <T> Map<String, T> loadAll(String pattern, Parser<T> parser) {
        Map<String, T> out = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    JsonNode n = JsonUtils.mapper().readTree(in);
                    T def = parser.parse(n);
                    String key =
                            def instanceof ModuleDef m
                                    ? m.key()
                                    : def instanceof QuestionnaireDef q ? q.key() : null;
                    if (key == null) {
                        throw new IllegalStateException("无法解析: " + r.getFilename());
                    }
                    if (out.put(key, def) != null) {
                        throw new IllegalStateException("重复定义: " + key);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载问卷资源失败: " + pattern, e);
        }
        return out;
    }
}
