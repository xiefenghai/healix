package com.healix.core.careplan.guideline;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 按患者事实从条目库筛出适用条目；禁忌命中的进入 excluded，供模型与校验区分。 */
@Component
@RequiredArgsConstructor
public class GuidelineMatcher {

    private final GuidelineLibrary library;

    public GuidelineMatch match(GuidelineFacts facts) {
        return match(library, facts);
    }

    public static GuidelineMatch match(GuidelineLibrary library, GuidelineFacts facts) {
        if (library == null || !library.enabled() || facts == null) {
            return GuidelineMatch.DISABLED;
        }
        List<GuidelineRec> matched = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<GuidelineMatch.Excluded> excluded = new ArrayList<>();
        for (GuidelineRec rec : library.usable()) {
            if (!rec.appliesWhen().matches(facts)) {
                continue;
            }
            if (rec.contraindicatedWhen() != null && rec.contraindicatedWhen().matches(facts)) {
                excluded.add(new GuidelineMatch.Excluded(rec, rec.contraindicatedWhen().describe()));
                continue;
            }
            matched.add(rec);
            labels.add(library.sourceLabel(rec));
        }
        return new GuidelineMatch(true, library.version(), List.copyOf(matched), List.copyOf(labels), List.copyOf(excluded));
    }
}
