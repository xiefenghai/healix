package com.healix.core.assessment.support;

import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.support.AssessmentResult.FactorGroup;
import com.healix.core.assessment.support.AssessmentResult.Hit;
import com.healix.core.assessment.support.AssessmentResult.Missing;
import com.healix.core.assessment.support.AssessmentResult.Unchecked;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 因素组收集：命中 / 未核实（三态：是 → 命中，否 → 忽略，未知 → 未核实，不编造命中）。 */
public final class FactorCollector {

    public static final String SRC_ARCHIVE = "ARCHIVE";
    public static final String SRC_METRIC = "METRIC";
    public static final String SRC_LAB = "LAB";
    public static final String SRC_MEDICATION = "MEDICATION";
    public static final String SRC_QUESTIONNAIRE = "QUESTIONNAIRE";
    public static final String SRC_DERIVED = "DERIVED";

    private final String code;
    private final String title;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Unchecked> unchecked = new ArrayList<>();

    public FactorCollector(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public FactorCollector hit(String code, String label, String detail, String source) {
        if (hits.stream().noneMatch(h -> h.code().equals(code))) {
            hits.add(new Hit(code, label, detail, source));
        }
        return this;
    }

    public FactorCollector unchecked(InputField field, String label) {
        if (unchecked.stream().noneMatch(u -> u.label().equals(label))) {
            unchecked.add(new Unchecked(
                    field == null ? null : field.name(), label, field != null && field.inQuestionnaire()));
        }
        return this;
    }

    /** value=true 命中；null 记未核实。 */
    public FactorCollector check(
            Boolean value, InputField field, String code, String label, String detail, String source) {
        if (Boolean.TRUE.equals(value)) {
            hit(code, label, detail, source);
        } else if (value == null) {
            unchecked(field, label);
        }
        return this;
    }

    /** 问卷是非题事实。 */
    public FactorCollector fact(AssessmentContext ctx, InputField field, String code, String label) {
        return check(ctx.fact(field), field, code, label, "问卷：是", SRC_QUESTIONNAIRE);
    }

    public boolean has(String hitCode) {
        return hits.stream().anyMatch(h -> h.code().equals(hitCode));
    }

    public int hitCount() {
        return hits.size();
    }

    public List<Hit> hits() {
        return hits;
    }

    public FactorGroup build() {
        return new FactorGroup(code, title, List.copyOf(hits), List.copyOf(unchecked));
    }

    public static List<Missing> missing(AssessmentContext ctx, Collection<InputField> required) {
        List<Missing> out = new ArrayList<>();
        for (InputField f : required) {
            if (!ctx.has(f)) {
                out.add(missingOf(f));
            }
        }
        return out;
    }

    public static Missing missingOf(InputField f) {
        return new Missing(f.name(), f.label(), f.fillHint(), f.inQuestionnaire());
    }
}
