package com.healix.core.science.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.healix.core.science.catalog.ScienceCatalog.Clip;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ScienceCatalogTest {

    @Test
    void nextUnwatchedFollowsCatalogOrder() {
        assertThat(ScienceCatalog.nextUnwatched(List.of()).code()).isEqualTo(ScienceCatalog.CLIPS.get(0).code());
        Set<String> all = ScienceCatalog.CLIPS.stream().map(Clip::code).collect(Collectors.toSet());
        assertThat(ScienceCatalog.nextUnwatched(all)).isNull();
    }

    @Test
    void replayIsStableForTheSameDay() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        Clip first = ScienceCatalog.replay(day);
        assertThat(ScienceCatalog.replay(day)).isEqualTo(first);
        assertThat(ScienceCatalog.CLIPS).contains(first);
    }
}
