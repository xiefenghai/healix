package com.healix.core.notify.service;

import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将 peopleId 展开为 C 账号收件人。 */
@Component
@RequiredArgsConstructor
public class NotifyRecipientResolver {

    private final AccountPatientMapper accountPatientMapper;

    public record Recipient(String accountId, String peopleId, String displayName) {}

    public List<Recipient> resolveCAccounts(String peopleId, List<String> explicitAccountIds) {
        Map<String, Recipient> byAccount = new LinkedHashMap<>();
        if (StringUtils.hasText(peopleId)) {
            for (AccountPatient card : accountPatientMapper.listByPeople(peopleId.trim())) {
                if (!StringUtils.hasText(card.getAccountId())) {
                    continue;
                }
                byAccount.putIfAbsent(
                        card.getAccountId(),
                        new Recipient(
                                card.getAccountId(),
                                card.getPeopleId(),
                                StringUtils.hasText(card.getDisplayName()) ? card.getDisplayName() : null));
            }
        }
        if (explicitAccountIds != null) {
            for (String accountId : explicitAccountIds) {
                if (!StringUtils.hasText(accountId)) {
                    continue;
                }
                byAccount.putIfAbsent(accountId.trim(), new Recipient(accountId.trim(), peopleId, null));
            }
        }
        return new ArrayList<>(byAccount.values());
    }

    public Set<String> distinctAccountIds(String peopleId) {
        Set<String> ids = new LinkedHashSet<>();
        for (Recipient r : resolveCAccounts(peopleId, null)) {
            ids.add(r.accountId());
        }
        return ids;
    }
}
