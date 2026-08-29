package com.healix.core.revision.service;

import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.domain.StaffRoleBinding;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.identity.mapper.StaffRoleBindingMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.revision.domain.FieldRevisionBatch;
import com.healix.core.revision.domain.FieldRevisionItem;
import com.healix.core.revision.dto.RevisionBatchViewDto;
import com.healix.core.revision.dto.RevisionItemViewDto;
import com.healix.core.revision.mapper.FieldRevisionBatchMapper;
import com.healix.core.revision.mapper.FieldRevisionItemMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RevisionQueryService {

    private final FieldRevisionBatchMapper batchMapper;
    private final FieldRevisionItemMapper itemMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffRoleBindingMapper staffRoleBindingMapper;
    private final PeopleProfileMapper peopleProfileMapper;

    public List<RevisionBatchViewDto> listRevisions(
            String tenantId, String peopleId, String bizType, String bizKey) {
        List<FieldRevisionBatch> batches =
                bizType == null || bizType.isBlank()
                        ? batchMapper.listByPeople(tenantId, peopleId)
                        : batchMapper.listByTarget(tenantId, peopleId, bizType, bizKey);
        List<RevisionBatchViewDto> out = new ArrayList<>();
        for (FieldRevisionBatch batch : batches) {
            RevisionBatchViewDto dto = new RevisionBatchViewDto();
            dto.setBatchId(batch.getId());
            dto.setOperatorType(batch.getOperatorType());
            dto.setOperatorId(batch.getOperatorId());
            dto.setBizType(batch.getBizType());
            dto.setBizKey(batch.getBizKey());
            dto.setVersionBefore(batch.getVersionBefore());
            dto.setVersionAfter(batch.getVersionAfter());
            dto.setGmtCreated(batch.getGmtCreated());
            List<RevisionItemViewDto> items = new ArrayList<>();
            for (FieldRevisionItem item : itemMapper.listByBatchId(batch.getId())) {
                RevisionItemViewDto iv = new RevisionItemViewDto();
                iv.setFieldPath(item.getFieldPath());
                iv.setOldValue(item.getOldValue());
                iv.setNewValue(item.getNewValue());
                iv.setOldDisplay(item.getOldDisplay());
                iv.setNewDisplay(item.getNewDisplay());
                items.add(iv);
            }
            dto.setItems(items);
            out.add(dto);
        }
        enrichOperatorNames(out);
        enrichOperatorRoles(out);
        return out;
    }

    private void enrichOperatorRoles(List<RevisionBatchViewDto> batches) {
        Set<String> staffIds = new HashSet<>();
        for (RevisionBatchViewDto batch : batches) {
            if ("STAFF".equals(batch.getOperatorType())
                    && batch.getOperatorId() != null
                    && !batch.getOperatorId().isBlank()) {
                staffIds.add(batch.getOperatorId());
            }
        }
        if (staffIds.isEmpty()) {
            return;
        }
        Map<String, String> roleByStaff = loadPrimaryStaffRoles(staffIds);
        for (RevisionBatchViewDto batch : batches) {
            if ("STAFF".equals(batch.getOperatorType())) {
                batch.setOperatorRoleCode(roleByStaff.get(batch.getOperatorId()));
            }
        }
    }

    private Map<String, String> loadPrimaryStaffRoles(Set<String> staffIds) {
        Map<String, List<String>> rolesByStaff = new HashMap<>();
        for (StaffRoleBinding binding : staffRoleBindingMapper.listByStaffIds(new ArrayList<>(staffIds))) {
            rolesByStaff
                    .computeIfAbsent(binding.getStaffId(), k -> new ArrayList<>())
                    .add(binding.getRoleCode());
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : rolesByStaff.entrySet()) {
            String picked = pickDisplayRole(entry.getValue());
            if (picked != null) {
                out.put(entry.getKey(), picked);
            }
        }
        return out;
    }

    /** 修订展示优先临床岗位，便于区分医生 / 健管师 */
    private static String pickDisplayRole(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return null;
        }
        for (StaffRoleEnum preferred :
                new StaffRoleEnum[] {
                    StaffRoleEnum.DOCTOR,
                    StaffRoleEnum.CARE_MANAGER,
                    StaffRoleEnum.TENANT_OPERATOR,
                    StaffRoleEnum.TENANT_ADMIN
                }) {
            if (roleCodes.contains(preferred.name())) {
                return preferred.name();
            }
        }
        return roleCodes.get(0);
    }

    private void enrichOperatorNames(List<RevisionBatchViewDto> batches) {
        Set<String> staffIds = new HashSet<>();
        Set<String> peopleIds = new HashSet<>();
        for (RevisionBatchViewDto batch : batches) {
            if (batch.getOperatorId() == null || batch.getOperatorId().isBlank()) {
                continue;
            }
            if ("STAFF".equals(batch.getOperatorType())) {
                staffIds.add(batch.getOperatorId());
            } else if ("PEOPLE".equals(batch.getOperatorType())) {
                peopleIds.add(batch.getOperatorId());
            }
        }
        Map<String, String> staffNames = loadStaffDisplayNames(staffIds);
        Map<String, String> peopleNames = loadPeopleDisplayNames(peopleIds);
        for (RevisionBatchViewDto batch : batches) {
            String operatorId = batch.getOperatorId();
            if (operatorId == null || operatorId.isBlank()) {
                continue;
            }
            if ("STAFF".equals(batch.getOperatorType())) {
                batch.setOperatorName(staffNames.get(operatorId));
            } else if ("PEOPLE".equals(batch.getOperatorType())) {
                batch.setOperatorName(peopleNames.get(operatorId));
            }
        }
    }

    private Map<String, String> loadStaffDisplayNames(Set<String> staffIds) {
        Map<String, String> names = new HashMap<>();
        for (String staffId : staffIds) {
            StaffProfile profile = staffProfileMapper.findById(staffId);
            if (profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
                names.put(staffId, profile.getDisplayName().trim());
            }
        }
        return names;
    }

    private Map<String, String> loadPeopleDisplayNames(Set<String> peopleIds) {
        Map<String, String> names = new HashMap<>();
        for (String peopleId : peopleIds) {
            PeopleProfile profile = peopleProfileMapper.findById(peopleId);
            if (profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
                names.put(peopleId, profile.getDisplayName().trim());
            }
        }
        return names;
    }
}
