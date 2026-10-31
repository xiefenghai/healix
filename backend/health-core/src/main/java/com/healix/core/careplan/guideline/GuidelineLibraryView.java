package com.healix.core.careplan.guideline;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B 端「依据来源」页与方案依据标签的只读视图。
 *
 * <p>只含条目转述与出处，不含指南原文；审定人只在这里展示，不进单份方案。
 */
public record GuidelineLibraryView(
        boolean enabled,
        boolean includesDraft,
        String version,
        List<SourceView> sources,
        List<RecView> recs,
        Stats stats,
        List<ReviewerView> reviewers) {

    public record SourceView(String code, String label, String name, String edition, String publisher, String status, int recCount) {}

    public record RecView(
            String code,
            int rev,
            String sourceCode,
            String sourceLabel,
            String kind,
            String block,
            String audience,
            String staffText,
            String constraint,
            String evidence,
            String status) {}

    public record Stats(int total, int safety, int target, Map<String, Integer> byAudience) {}

    public record ReviewerView(String name, String title, LocalDate lastReviewedAt, int recCount) {}

    /** 只列当前会进匹配的条目（生产 = 已审定）。 */
    public static GuidelineLibraryView of(GuidelineLibrary library) {
        List<GuidelineRec> recs = library.usable().stream()
                .sorted(Comparator.comparing(GuidelineRec::code))
                .toList();
        List<RecView> recViews = new ArrayList<>();
        Map<String, Integer> byAudience = new LinkedHashMap<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        Map<String, ReviewerView> reviewers = new LinkedHashMap<>();
        int safety = 0;
        for (GuidelineRec r : recs) {
            String audience = audience(r);
            recViews.add(new RecView(
                    r.code(),
                    r.rev(),
                    r.sourceCode(),
                    library.sourceLabel(r),
                    r.kind(),
                    r.planBlock(),
                    audience,
                    r.staffText(),
                    r.quant() == null ? null : r.quant().describe(),
                    r.evidence(),
                    r.status()));
            byAudience.merge(audience, 1, Integer::sum);
            bySource.merge(r.sourceCode(), 1, Integer::sum);
            if (r.safety()) {
                safety++;
            }
            if (r.reviewer() != null) {
                ReviewerView prev = reviewers.get(r.reviewer());
                LocalDate last = prev == null || r.reviewedAt() != null && r.reviewedAt().isAfter(prev.lastReviewedAt())
                        ? r.reviewedAt()
                        : prev.lastReviewedAt();
                reviewers.put(r.reviewer(), new ReviewerView(
                        r.reviewer(),
                        r.reviewerTitle() != null ? r.reviewerTitle() : prev == null ? null : prev.title(),
                        last,
                        (prev == null ? 0 : prev.recCount()) + 1));
            }
        }
        List<SourceView> sources = new ArrayList<>();
        library.sources().values().forEach(s -> sources.add(new SourceView(
                s.code(), s.label(), s.name(), s.edition(), s.publisher(), s.status(), bySource.getOrDefault(s.code(), 0))));
        return new GuidelineLibraryView(
                library.enabled(),
                library.includesDraft(),
                library.version(),
                sources,
                recViews,
                new Stats(recs.size(), safety, recs.size() - safety, byAudience),
                List.copyOf(reviewers.values()));
    }

    static String audience(GuidelineRec r) {
        GuidelineCondition c = r.appliesWhen();
        if (c.diseaseTags().isEmpty()) {
            return "通用";
        }
        return String.join("+", c.diseaseTags().stream().sorted().map(GuidelineCondition::diseaseLabel).toList());
    }
}
