package com.healix.core.archive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.dto.ArchiveCompletenessDto;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.archive.support.ArchiveCompletenessScorer;
import com.healix.core.archive.support.ArchiveCompletenessScorer.Score;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.service.DictService;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 实时计算患者档案完整度。
 *
 * <p>口径：基础档案 FIELD 字典全部字段为分母；若已创建病种档案，再叠加该病种 DISEASE_FIELD。
 * 无病种档案时仅计基础档案。
 */
@Service
@RequiredArgsConstructor
public class ArchiveCompletenessService {

    private final ArchiveAccessService archiveAccessService;
    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final DictService dictService;

    public ArchiveCompletenessDto compute(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);

        JsonNode basicContent = loadBasicContent(tenantId, peopleId);
        List<DictItemDto> basicFields = dictService.listFieldsForBasicArchive(tenantId);
        Score basic = ArchiveCompletenessScorer.score(basicFields, basicContent);

        ArchiveCompletenessDto dto = new ArchiveCompletenessDto();
        dto.setPeopleId(peopleId);
        dto.setBasicFilledCount(basic.filled());
        dto.setBasicTotalCount(basic.total());

        Score total = basic;
        for (PeopleDiseaseArchive row : diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId)) {
            String diseaseCode = row.getDiseaseCode();
            if (diseaseCode == null || diseaseCode.isBlank()) {
                continue;
            }
            JsonNode diseaseContent = parseContent(row.getContentJson());
            List<DictItemDto> diseaseFields = dictService.listFieldsForDisease(tenantId, diseaseCode);
            Score diseaseScore = ArchiveCompletenessScorer.score(diseaseFields, diseaseContent);

            ArchiveCompletenessDto.DiseaseSection section = new ArchiveCompletenessDto.DiseaseSection();
            section.setDiseaseCode(diseaseCode);
            section.setFilledCount(diseaseScore.filled());
            section.setTotalCount(diseaseScore.total());
            section.setPercent(diseaseScore.percent());
            dto.getDiseases().add(section);

            total = total.plus(diseaseScore);
        }

        dto.setFilledCount(total.filled());
        dto.setTotalCount(total.total());
        dto.setPercent(total.percent());
        return dto;
    }

    private JsonNode loadBasicContent(String tenantId, String peopleId) {
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null) {
            return JsonUtils.emptyObject();
        }
        return parseContent(archive.getContentJson());
    }

    private static JsonNode parseContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return JsonUtils.emptyObject();
        }
        JsonNode node = JsonUtils.readTree(contentJson);
        return node == null || node.isNull() ? JsonUtils.emptyObject() : node;
    }
}
