package com.healix.core.intake.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.intake.domain.IntakeAccessLink;
import com.healix.core.intake.mapper.IntakeAccessLinkMapper;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.people.support.MobileNumbers;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 首诊填写 Magic Link：签发 / 预览 / 兑换 / 撤销。 */
@Service
@RequiredArgsConstructor
public class IntakeAccessLinkService {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_VALID_DAYS = 7;
    private static final int REDEEM_LIMIT_PER_HOUR = 20;

    private final IntakeAccessLinkMapper linkMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final OrganizationMapper organizationMapper;
    private final PatientOrgMembershipMapper membershipMapper;

    @Value("${healix.public.user-base-url:http://localhost:5173}")
    private String userBaseUrl;

    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    /** 常见复姓（长的优先匹配） */
    private static final List<String> COMPOUND_SURNAMES = List.of(
            "欧阳", "太史", "端木", "上官", "司马", "东方", "独孤", "南宫", "万俟", "闻人",
            "夏侯", "诸葛", "尉迟", "公羊", "赫连", "澹台", "皇甫", "宗政", "濮阳", "公冶",
            "太叔", "申屠", "公孙", "慕容", "仲孙", "钟离", "长孙", "宇文", "司徒", "鲜于",
            "司空", "闾丘", "子车", "亓官", "司寇", "巫马", "公西", "颛孙", "壤驷", "公良",
            "漆雕", "乐正", "宰父", "谷梁", "拓跋", "夹谷", "轩辕", "令狐", "段干", "百里",
            "呼延", "东郭", "南门", "羊舌", "微生", "梁丘", "左丘", "西门", "第五", "南荣",
            "东里", "东宫", "仲长", "子书", "子桑", "即墨", "达奚", "褚师");

    /** key = ip|token → 小时桶计数 */
    private final ConcurrentHashMap<String, RedeemBucket> redeemBuckets = new ConcurrentHashMap<>();

    @Transactional
    public IssueResult issue(
            String tenantId, String orgId, String peopleId, String staffId, Integer validDays) {
        PeopleProfile people = requirePeople(tenantId, peopleId);
        Organization org = requireOrg(tenantId, orgId);
        requireActiveMembership(orgId, peopleId);

        int days = validDays != null && validDays > 0 ? validDays : DEFAULT_VALID_DAYS;
        LocalDateTime now = LocalDateTime.now();
        linkMapper.revokeOpenByPeople(peopleId, orgId, now);

        IntakeAccessLink link = new IntakeAccessLink();
        link.setTenantId(tenantId);
        link.setOrgId(orgId);
        link.setPeopleId(peopleId);
        link.setToken(generateToken());
        link.setStatus(STATUS_OPEN);
        link.setRedeemCount(0);
        link.setCreatedByStaffId(staffId);
        EntityMeta.onCreate(link);
        link.setExpireAt(link.getGmtCreated().plusDays(days));
        linkMapper.insert(link);

        String url = buildUrl(link.getToken());
        String display = politeAddress(people.getDisplayName(), people.getGender(), people.getBirthday());
        String copyText =
                "您好，" + display + "，请点击链接完成初步评估（约几分钟，" + days + "天内有效）：\n" + url;
        return new IssueResult(
                link.getId(),
                link.getToken(),
                url,
                link.getExpireAt(),
                copyText,
                org.getName(),
                display);
    }

    public PreviewResult preview(String token) {
        IntakeAccessLink link = requireLinkByToken(token);
        refreshExpired(link);
        PeopleProfile people = peopleProfileMapper.findById(link.getPeopleId());
        Organization org = organizationMapper.findById(link.getOrgId());
        boolean needMobileLast4 = hasSelfMobile(people);
        String status = link.getStatus();
        boolean usable = STATUS_OPEN.equals(status)
                && (link.getExpireAt() == null || !link.getExpireAt().isBefore(LocalDateTime.now()));
        String label = people == null
                ? "患者"
                : politeAddress(people.getDisplayName(), people.getGender(), people.getBirthday());
        return new PreviewResult(
                usable,
                status,
                link.getExpireAt(),
                org != null ? org.getName() : null,
                label,
                needMobileLast4);
    }

    @Transactional
    public RedeemContext redeem(String token, String mobileLast4, String clientIp) {
        checkRedeemRate(clientIp, token);
        IntakeAccessLink link = requireLinkByToken(token);
        refreshExpired(link);
        if (!STATUS_OPEN.equals(link.getStatus())) {
            throw new BusinessException(410, "链接已失效，请联系健管师重发");
        }
        if (link.getExpireAt() != null && link.getExpireAt().isBefore(LocalDateTime.now())) {
            linkMapper.markExpired(link.getId(), LocalDateTime.now());
            throw new BusinessException(410, "链接已过期，请联系健管师重发");
        }

        PeopleProfile people = requirePeople(link.getTenantId(), link.getPeopleId());
        if (hasSelfMobile(people)) {
            String expected = MobileNumbers.tail(MobileNumbers.trySelf(people.getMobile()));
            String given = mobileLast4 == null ? "" : mobileLast4.trim();
            if (!expected.equals(given)) {
                throw new BusinessException(403, "手机号后四位不正确");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int n = linkMapper.incrementRedeem(link.getId(), now, now);
        if (n <= 0) {
            throw new BusinessException(410, "链接已失效，请联系健管师重发");
        }

        return new RedeemContext(
                link.getId(),
                link.getTenantId(),
                link.getOrgId(),
                link.getPeopleId(),
                "MINIMAL");
    }

    @Transactional
    public void revoke(String tenantId, String orgId, String peopleId, String linkId) {
        IntakeAccessLink link = linkMapper.findById(linkId);
        if (link == null
                || !peopleId.equals(link.getPeopleId())
                || !orgId.equals(link.getOrgId())
                || !tenantId.equals(link.getTenantId())) {
            throw new BusinessException(404, "链接不存在");
        }
        if (!STATUS_OPEN.equals(link.getStatus())) {
            return;
        }
        linkMapper.revokeById(linkId, LocalDateTime.now());
    }

    public String buildUrl(String token) {
        String base = userBaseUrl == null ? "" : userBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/intake/t/" + token;
    }

    private void refreshExpired(IntakeAccessLink link) {
        if (STATUS_OPEN.equals(link.getStatus())
                && link.getExpireAt() != null
                && link.getExpireAt().isBefore(LocalDateTime.now())) {
            linkMapper.markExpired(link.getId(), LocalDateTime.now());
            link.setStatus(STATUS_EXPIRED);
        }
    }

    private void checkRedeemRate(String clientIp, String token) {
        String ip = StringUtils.hasText(clientIp) ? clientIp : "unknown";
        String key = ip + "|" + token;
        long hour = Instant.now().getEpochSecond() / 3600;
        RedeemBucket bucket = redeemBuckets.compute(key, (k, old) -> {
            if (old == null || old.hour != hour) {
                return new RedeemBucket(hour);
            }
            return old;
        });
        if (bucket.count.incrementAndGet() > REDEEM_LIMIT_PER_HOUR) {
            throw new BusinessException(429, "尝试次数过多，请稍后再试");
        }
        // 偶尔清理，避免 map 无限增长
        if (redeemBuckets.size() > 5000) {
            redeemBuckets.entrySet().removeIf(e -> e.getValue().hour < hour - 1);
        }
    }

    private IntakeAccessLink requireLinkByToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(404, "链接不存在");
        }
        IntakeAccessLink link = linkMapper.findByToken(token.trim());
        if (link == null) {
            throw new BusinessException(404, "链接不存在");
        }
        return link;
    }

    private PeopleProfile requirePeople(String tenantId, String peopleId) {
        PeopleProfile people = peopleProfileMapper.findById(peopleId);
        if (people == null || !tenantId.equals(people.getTenantId())) {
            throw new BusinessException(404, "患者不存在");
        }
        return people;
    }

    private Organization requireOrg(String tenantId, String orgId) {
        Organization org = organizationMapper.findById(orgId);
        if (org == null || !tenantId.equals(org.getTenantId())) {
            throw new BusinessException(404, "机构不存在");
        }
        return org;
    }

    private void requireActiveMembership(String orgId, String peopleId) {
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(403, "患者未在本机构有效入组");
        }
    }

    private static boolean hasSelfMobile(PeopleProfile people) {
        if (people == null) {
            return false;
        }
        return MobileNumbers.trySelf(people.getMobile()) != null && "SELF".equals(people.getMobileOwner());
    }

    /**
     * 分享/落地页称呼：贺先生、王阿姨、李叔叔。
     * 女：&lt;50 女士，≥50 阿姨；男：&lt;45 先生，45–64 叔叔，≥65 伯伯；性别不明默认先生。
     */
    static String politeAddress(String rawName, String gender, LocalDate birthday) {
        String surname = extractSurname(rawName);
        if (!StringUtils.hasText(surname)) {
            return "患者";
        }
        return surname + pickHonorific(gender, ageYears(birthday));
    }

    static String extractSurname(String raw) {
        String name = MobileNumbers.trimName(raw);
        if (!StringUtils.hasText(name)) {
            return "";
        }
        for (String compound : COMPOUND_SURNAMES) {
            if (name.startsWith(compound)) {
                return compound;
            }
        }
        return name.substring(0, 1);
    }

    static String pickHonorific(String gender, Integer age) {
        String g = gender == null ? "" : gender.trim().toUpperCase();
        boolean female = "FEMALE".equals(g) || "女".equals(gender);
        boolean male = "MALE".equals(g) || "男".equals(gender);
        if (female) {
            return age != null && age >= 50 ? "阿姨" : "女士";
        }
        if (male) {
            if (age != null && age >= 65) {
                return "伯伯";
            }
            if (age != null && age >= 45) {
                return "叔叔";
            }
            return "先生";
        }
        // 性别不明：偏年长用叔叔，其余先生
        if (age != null && age >= 55) {
            return "叔叔";
        }
        return "先生";
    }

    static Integer ageYears(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        return Period.between(birthday, LocalDate.now(CN_ZONE)).getYears();
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class RedeemBucket {
        final long hour;
        final AtomicInteger count = new AtomicInteger();

        RedeemBucket(long hour) {
            this.hour = hour;
        }
    }

    public record IssueResult(
            String id,
            String token,
            String url,
            LocalDateTime expireAt,
            String copyText,
            String orgName,
            String patientLabel) {}

    public record PreviewResult(
            boolean usable,
            String status,
            LocalDateTime expireAt,
            String orgName,
            String patientLabel,
            boolean needMobileLast4) {}

    public record RedeemContext(
            String linkId, String tenantId, String orgId, String peopleId, String depthDefault) {}
}
