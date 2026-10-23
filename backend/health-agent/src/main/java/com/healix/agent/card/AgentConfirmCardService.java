package com.healix.agent.card;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.service.VitalService;
import com.healix.core.vitals.service.VitalService.PatientReading;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 确认卡的发放、改场景、确认与取消。 */
@Service
@RequiredArgsConstructor
public class AgentConfirmCardService {

    static final String TYPE_VITAL = "VITAL";
    static final String PENDING = "PENDING";
    static final String CONFIRMED = "CONFIRMED";
    static final String CANCELLED = "CANCELLED";
    static final String EXPIRED = "EXPIRED";

    private final AgentConfirmCardMapper cardMapper;
    private final VitalService vitalService;

    public record PrepareResult(boolean handled, String reply, List<AgentConfirmCardView> cards) {
        public static PrepareResult pass() {
            return new PrepareResult(false, "", List.of());
        }
    }

    public record AgentConfirmCardView(
            String id, String cardType, String summary, String metric, String bpContext, String mealContext) {}

    /** 新会话时作废还没确认的卡。 */
    @Transactional
    public void discardPending(String peopleId) {
        cardMapper.expirePending(peopleId);
    }

    /** 下一条消息先作废未确认的卡，再按原话出新卡。 */
    @Transactional
    public PrepareResult prepare(String tenantId, String peopleId, String message) {
        cardMapper.expirePending(peopleId);
        VitalMentionParser.Outcome outcome = VitalMentionParser.parse(message);
        if (!outcome.handled()) {
            return PrepareResult.pass();
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        List<AgentConfirmCardView> views = new ArrayList<>();
        for (VitalCardPayload payload : outcome.cards()) {
            payload.setPreviousText(previousText(tenantId, peopleId, payload, now));
            views.add(insert(tenantId, peopleId, payload, now.plusMinutes(10)));
        }
        return new PrepareResult(true, outcome.reply(), views);
    }

    @Transactional
    public AgentConfirmCardView updateContext(String peopleId, String cardId, String bpContext, String mealContext) {
        AgentConfirmCard card = requirePending(peopleId, cardId);
        VitalCardPayload payload = readPayload(card);
        if ("BP".equals(payload.getMetric())) {
            if (!"HOME".equals(bpContext) && !"CLINIC".equals(bpContext)) {
                throw new BusinessException("请选择家庭自测或诊室");
            }
            payload.setBpContext(bpContext);
        } else if ("GLUCOSE".equals(payload.getMetric())) {
            if (!"FASTING".equals(mealContext) && !"POSTPRANDIAL".equals(mealContext) && !"RANDOM".equals(mealContext)) {
                throw new BusinessException("请选择空腹、餐后或随机");
            }
            payload.setMealContext(mealContext);
        } else {
            throw new BusinessException("这张卡不能改场景");
        }
        card.setPayloadJson(JsonUtils.toJson(payload));
        card.setSummary(VitalCardTexts.summary(payload));
        EntityMeta.onUpdate(card);
        cardMapper.update(card);
        return toView(card, payload);
    }

    /** 按卡上的数字写入。不把方案任务打成完成。 */
    @Transactional
    public String confirm(String peopleId, String cardId) {
        AgentConfirmCard card = requirePending(peopleId, cardId);
        VitalCardPayload payload = readPayload(card);
        LocalDateTime recordedAt = LocalDateTime.now(JobCronSupport.ZONE);
        List<PatientReading> readings = toReadings(payload);
        vitalService.recordPatientReadings(peopleId, readings, recordedAt, false);
        card.setStatus(CONFIRMED);
        EntityMeta.onUpdate(card);
        cardMapper.update(card);
        return confirmedReply(payload);
    }

    private static String confirmedReply(VitalCardPayload payload) {
        return switch (payload.getMetric()) {
            case "BP" -> "已记下"
                    + VitalCardTexts.bpLabel(payload.getBpContext())
                    + "血压 "
                    + VitalCardTexts.number(payload.getSys())
                    + "/"
                    + VitalCardTexts.number(payload.getDia())
                    + "。";
            case "GLUCOSE" -> "已记下"
                    + VitalCardTexts.mealLabel(payload.getMealContext())
                    + "血糖 "
                    + VitalCardTexts.number(payload.getValue())
                    + " mmol/L。";
            case "WEIGHT" -> "已记下体重 "
                    + (payload.getJinRaw() == null
                            ? VitalCardTexts.number(payload.getValue())
                            : payload.getValue().setScale(1, java.math.RoundingMode.HALF_UP).toPlainString())
                    + " kg。";
            default -> "已记下。";
        };
    }

    @Transactional
    public void cancel(String peopleId, String cardId) {
        AgentConfirmCard card = requirePending(peopleId, cardId);
        card.setStatus(CANCELLED);
        EntityMeta.onUpdate(card);
        cardMapper.update(card);
    }

    private AgentConfirmCardView insert(String tenantId, String peopleId, VitalCardPayload payload, LocalDateTime expiresAt) {
        AgentConfirmCard card = new AgentConfirmCard();
        card.setTenantId(tenantId);
        card.setPeopleId(peopleId);
        card.setCardType(TYPE_VITAL);
        card.setStatus(PENDING);
        card.setPayloadJson(JsonUtils.toJson(payload));
        card.setSummary(VitalCardTexts.summary(payload));
        card.setExpiresAt(expiresAt);
        EntityMeta.onCreate(card);
        cardMapper.insert(card);
        return toView(card, payload);
    }

    private AgentConfirmCard requirePending(String peopleId, String cardId) {
        AgentConfirmCard card = cardMapper.findById(cardId);
        if (card == null || !peopleId.equals(card.getPeopleId())) {
            throw new BusinessException("这张卡不存在");
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        if (PENDING.equals(card.getStatus()) && card.getExpiresAt() != null && card.getExpiresAt().isBefore(now)) {
            card.setStatus(EXPIRED);
            EntityMeta.onUpdate(card);
            cardMapper.update(card);
        }
        if (!PENDING.equals(card.getStatus())) {
            throw new BusinessException("这张卡已经不能用了，请再说一次");
        }
        return card;
    }

    private static VitalCardPayload readPayload(AgentConfirmCard card) {
        VitalCardPayload payload = JsonUtils.fromJson(card.getPayloadJson(), VitalCardPayload.class);
        if (payload == null || !StringUtils.hasText(payload.getMetric())) {
            throw new BusinessException("这张卡不存在");
        }
        return payload;
    }

    private static List<PatientReading> toReadings(VitalCardPayload payload) {
        if ("BP".equals(payload.getMetric())) {
            return List.of(
                    reading(MetricTypeEnum.BLOOD_PRESSURE_SYS.name(), payload.getSys(), "mmHg", null, payload.getBpContext()),
                    reading(MetricTypeEnum.BLOOD_PRESSURE_DIA.name(), payload.getDia(), "mmHg", null, payload.getBpContext()));
        }
        if ("GLUCOSE".equals(payload.getMetric())) {
            return List.of(reading(
                    MetricTypeEnum.BLOOD_GLUCOSE.name(), payload.getValue(), "mmol/L", payload.getMealContext(), null));
        }
        if ("WEIGHT".equals(payload.getMetric())) {
            return List.of(reading(MetricTypeEnum.WEIGHT.name(), payload.getValue(), "kg", null, null));
        }
        throw new BusinessException("这张卡不存在");
    }

    private static PatientReading reading(
            String metricType, BigDecimal value, String unit, String mealContext, String bpContext) {
        return new PatientReading(metricType, value, unit, VitalSourceEnum.SELF.name(), mealContext, bpContext);
    }

    private String previousText(String tenantId, String peopleId, VitalCardPayload payload, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay().minusSeconds(1);
        String text = switch (payload.getMetric()) {
            case "BP" -> previousBp(tenantId, peopleId, from, to);
            case "GLUCOSE" -> previousSingle(tenantId, peopleId, MetricTypeEnum.BLOOD_GLUCOSE.name(), from, to, true);
            case "WEIGHT" -> previousSingle(tenantId, peopleId, MetricTypeEnum.WEIGHT.name(), from, to, false);
            default -> "";
        };
        return StringUtils.hasText(text) ? text : "";
    }

    private String previousBp(String tenantId, String peopleId, LocalDateTime from, LocalDateTime to) {
        List<VitalRecord> sys = vitalService.list(tenantId, peopleId, MetricTypeEnum.BLOOD_PRESSURE_SYS.name(), from, to);
        List<VitalRecord> dia = vitalService.list(tenantId, peopleId, MetricTypeEnum.BLOOD_PRESSURE_DIA.name(), from, to);
        if (sys.isEmpty()) {
            return "";
        }
        VitalRecord s = sys.get(0);
        VitalRecord d = matchDia(s, dia);
        if (d == null) {
            return "";
        }
        return "今天已有 " + VitalCardTexts.number(s.getValue()) + "/" + VitalCardTexts.number(d.getValue()) + "。";
    }

    private static VitalRecord matchDia(VitalRecord sys, List<VitalRecord> dias) {
        for (VitalRecord d : dias) {
            if (StringUtils.hasText(sys.getGroupId()) && sys.getGroupId().equals(d.getGroupId())) {
                return d;
            }
        }
        if (sys.getRecordedAt() == null) {
            return null;
        }
        LocalDateTime minute = sys.getRecordedAt().truncatedTo(ChronoUnit.MINUTES);
        for (VitalRecord d : dias) {
            if (d.getRecordedAt() != null && minute.equals(d.getRecordedAt().truncatedTo(ChronoUnit.MINUTES))) {
                return d;
            }
        }
        return null;
    }

    private String previousSingle(
            String tenantId, String peopleId, String metricType, LocalDateTime from, LocalDateTime to, boolean glucose) {
        List<VitalRecord> rows = vitalService.list(tenantId, peopleId, metricType, from, to);
        if (rows.isEmpty()) {
            return "";
        }
        VitalRecord row = rows.get(0);
        if (glucose) {
            String meal = mealOf(row);
            return "今天已有 " + VitalCardTexts.number(row.getValue()) + "（" + VitalCardTexts.mealLabel(meal) + "）。";
        }
        return "今天已有 " + VitalCardTexts.number(row.getValue()) + " kg。";
    }

    private static String mealOf(VitalRecord row) {
        if (!StringUtils.hasText(row.getExtraJson())) {
            return "RANDOM";
        }
        var node = JsonUtils.readTree(row.getExtraJson()).path("mealContext");
        return node.isMissingNode() ? "RANDOM" : node.asText("RANDOM");
    }

    private static AgentConfirmCardView toView(AgentConfirmCard card, VitalCardPayload payload) {
        return new AgentConfirmCardView(
                card.getId(),
                card.getCardType(),
                card.getSummary(),
                payload.getMetric(),
                payload.getBpContext(),
                payload.getMealContext());
    }
}
