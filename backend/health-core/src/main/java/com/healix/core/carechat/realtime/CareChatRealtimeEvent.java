package com.healix.core.carechat.realtime;

import com.healix.core.carechat.dto.CareChatMessageDto;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 沟通实时事件：未读变更 / 新消息。 */
@Getter
@Setter
public class CareChatRealtimeEvent {

    public static final String TYPE_UNREAD = "unread";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_READ = "read";

    private String type;
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String threadId;
    /** 机构侧未读合计（B 侧栏） */
    private long staffUnreadTotal;
    /** 该会话机构侧未读 */
    private int staffUnreadCount;
    /** 该会话患者侧未读 */
    private int patientUnreadCount;
    private CareChatMessageDto message;
    /** C 端需唤醒的账号（people 绑定的全部 account） */
    private List<String> patientAccountIds = new ArrayList<>();
}
