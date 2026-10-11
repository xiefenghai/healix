package com.healix.core.carechat.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** B 端沟通收件箱（按健管组分组）。 */
@Getter
@Setter
public class CareChatInboxDto {
    private int totalUnread;
    private int patientWithUnread;
    /** 最近沟通过的患者（有消息，按 lastMessageAt 倒序） */
    private List<PatientItem> recent = new ArrayList<>();
    private List<Group> groups = new ArrayList<>();

    @Getter
    @Setter
    public static class Group {
        private String careTeamId;
        private String careTeamName;
        private int unreadCount;
        private int patientCount;
        private List<PatientItem> patients = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class PatientItem {
        private String peopleId;
        private String displayName;
        private String careTeamId;
        private String careTeamName;
        private boolean patientLinked;
        private boolean hasThread;
        private int staffUnreadCount;
        private String lastMessagePreview;
        private String lastSenderType;
        private LocalDateTime lastMessageAt;
    }
}
