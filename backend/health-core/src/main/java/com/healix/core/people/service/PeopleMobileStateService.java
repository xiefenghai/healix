package com.healix.core.people.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.util.JsonUtils;
import com.healix.core.people.domain.PeopleMobileState;
import com.healix.core.people.mapper.PeopleMobileStateMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 无本人号标记，以及关联时留下的待核对字段。 */
@Service
@RequiredArgsConstructor
public class PeopleMobileStateService {

    private final PeopleMobileStateMapper mapper;

    public boolean isNoSelf(String peopleId) {
        PeopleMobileState state = mapper.findByPeople(peopleId);
        return state != null && state.getNoSelfMobile() != null && state.getNoSelfMobile() == 1;
    }

    public void setNoSelf(String peopleId, String tenantId, boolean noSelf) {
        PeopleMobileState state = load(peopleId, tenantId);
        state.setNoSelfMobile(noSelf ? 1 : 0);
        state.setGmtModified(LocalDateTime.now());
        mapper.upsert(state);
    }

    public void putReview(String peopleId, String tenantId, String key, String value) {
        PeopleMobileState state = load(peopleId, tenantId);
        Map<String, String> review = readReview(state.getDemoReviewJson());
        if (!StringUtils.hasText(value)) {
            review.remove(key);
        } else {
            review.put(key, value);
        }
        state.setDemoReviewJson(review.isEmpty() ? null : JsonUtils.toJson(review));
        state.setGmtModified(LocalDateTime.now());
        mapper.upsert(state);
    }

    public void clearReview(String peopleId, String tenantId, String... keys) {
        PeopleMobileState state = mapper.findByPeople(peopleId);
        if (state == null || !StringUtils.hasText(state.getDemoReviewJson())) {
            return;
        }
        Map<String, String> review = readReview(state.getDemoReviewJson());
        for (String key : keys) {
            review.remove(key);
        }
        state.setDemoReviewJson(review.isEmpty() ? null : JsonUtils.toJson(review));
        state.setGmtModified(LocalDateTime.now());
        mapper.upsert(state);
    }

    public String reviewSummary(String peopleId) {
        PeopleMobileState state = mapper.findByPeople(peopleId);
        if (state == null) {
            return null;
        }
        Map<String, String> review = readReview(state.getDemoReviewJson());
        if (review.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        append(sb, review, "displayName", "姓名");
        append(sb, review, "gender", "性别");
        append(sb, review, "birthday", "出生日期");
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void append(StringBuilder sb, Map<String, String> review, String key, String label) {
        if (!review.containsKey(key)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('；');
        }
        String value = review.get(key);
        if ("gender".equals(key)) {
            value = "MALE".equals(value) ? "男" : "FEMALE".equals(value) ? "女" : value;
        }
        sb.append(label).append("待核对");
        if (StringUtils.hasText(value)) {
            sb.append("（提交值 ").append(value).append('）');
        }
    }

    private PeopleMobileState load(String peopleId, String tenantId) {
        PeopleMobileState state = mapper.findByPeople(peopleId);
        if (state != null) {
            return state;
        }
        state = new PeopleMobileState();
        state.setPeopleId(peopleId);
        state.setTenantId(tenantId);
        state.setNoSelfMobile(0);
        return state;
    }

    private static Map<String, String> readReview(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> map = JsonUtils.fromJson(json, new TypeReference<>() {});
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }
}
