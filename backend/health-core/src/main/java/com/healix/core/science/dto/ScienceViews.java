package com.healix.core.science.dto;

import java.util.List;

/** C 端今日科普。 */
public final class ScienceViews {

    private ScienceViews() {}

    public record ClipView(String code, String title, boolean watched, String watchedBizDate) {}

    public record Today(
            boolean enabled,
            boolean eligible,
            boolean opened,
            boolean watched,
            boolean replay,
            String holdText,
            String disclaimer,
            ClipView clip) {}

    public record Overview(
            boolean enabled,
            int watchedCount,
            int totalCount,
            String disclaimer,
            boolean canOpen,
            boolean replay,
            String holdText,
            ClipView today,
            List<ClipView> clips) {}
}
