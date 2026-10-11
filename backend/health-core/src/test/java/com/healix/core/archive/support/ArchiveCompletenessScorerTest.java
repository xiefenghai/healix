package com.healix.core.archive.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.common.util.JsonUtils;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.support.DictFieldSchemas;
import com.healix.core.metadata.enums.MetaDataCodeEnum;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchiveCompletenessScorerTest {

    @Test
    void emptyContentScoresZero() {
        List<DictItemDto> fields = List.of(
                field("presentIllness", DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.BASIC_PRESENT_ILLNESS, "现有疾病", "presentIllness")),
                field("familyHistory", DictFieldSchemas.textField(
                        MetaDataCodeEnum.BASIC_FAMILY_HISTORY, "家族史")));
        ArchiveCompletenessScorer.Score score =
                ArchiveCompletenessScorer.score(fields, JsonUtils.emptyObject());
        assertEquals(0, score.filled());
        assertEquals(2, score.total());
        assertEquals(0, score.percent());
    }

    @Test
    void familyHistoryNoneStatusCountsAsFilled() {
        var root = JsonUtils.emptyObject();
        root.put("familyHistoryStatus", "none");
        assertTrue(ArchiveCompletenessScorer.isFamilyHistoryFilled(root));

        List<DictItemDto> fields = List.of(field(
                "familyHistory",
                DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_FAMILY_HISTORY, "家族史")));
        ArchiveCompletenessScorer.Score score = ArchiveCompletenessScorer.score(fields, root);
        assertEquals(1, score.filled());
        assertEquals(1, score.total());
        assertEquals(100, score.percent());
    }

    @Test
    void compositeExpandsSubFields() {
        String schema = DictFieldSchemas.compositeField(
                MetaDataCodeEnum.BASIC_DIET,
                "饮食情况",
                DictFieldSchemas.fields(
                        DictFieldSchemas.subSelectField("appetite", "食欲", "appetiteLevel"),
                        DictFieldSchemas.subTextField("preference", "饮食偏好"),
                        DictFieldSchemas.subTextField("note", "备注")));
        List<DictItemDto> fields = List.of(field("diet", schema));

        var root = JsonUtils.emptyObject();
        var diet = root.putObject("diet");
        diet.put("appetite", "NORMAL");

        ArchiveCompletenessScorer.Score score = ArchiveCompletenessScorer.score(fields, root);
        assertEquals(1, score.filled());
        assertEquals(3, score.total());
        assertEquals(33, score.percent());
    }

    @Test
    void nestedSmokingObjectCountsAsFilled() {
        String schema = DictFieldSchemas.compositeField(
                MetaDataCodeEnum.BASIC_LIFESTYLE,
                "生活习惯",
                DictFieldSchemas.fields(
                        DictFieldSchemas.subSelectField("smoking", "吸烟", "smoking"),
                        DictFieldSchemas.subSelectField("drinking", "饮酒", "drinking")));
        List<DictItemDto> fields = List.of(field("lifestyle", schema));

        var root = JsonUtils.emptyObject();
        var lifestyle = root.putObject("lifestyle");
        lifestyle.putObject("smoking").put("status", "NEVER");

        ArchiveCompletenessScorer.Score score = ArchiveCompletenessScorer.score(fields, root);
        assertEquals(1, score.filled());
        assertEquals(2, score.total());
    }

    @Test
    void pastHistoryNoneStatusCountsAsFilled() {
        var root = JsonUtils.emptyObject();
        root.put("pastHistoryStatus", "none");
        assertTrue(ArchiveCompletenessScorer.isPastHistoryFilled(root, "pastHistory"));
        assertTrue(ArchiveCompletenessScorer.isPastHistoryFilled(root, "pastHistoryItems"));
        assertFalse(ArchiveCompletenessScorer.isFamilyHistoryFilled(root));
    }

    private static DictItemDto field(String code, String content) {
        DictItemDto dto = new DictItemDto();
        dto.setDictCode(code);
        dto.setContent(content);
        return dto;
    }
}
