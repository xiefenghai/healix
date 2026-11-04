package com.healix.agent.extract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 核对卡。挂在会话消息上，确认前不写库。 */
@Getter
@Setter
public class ChatExtractCard {
    private String messageId;
    private String peopleId;
    /** OPEN / CONFIRMED / DISCARDED / SUPERSEDED */
    private String status = "OPEN";
    /** 这张卡已经写入的条数。剩下的可以继续确认或丢弃。 */
    private int writtenCount;
    private String title = "从对话整理的记录";
    private String banner = "这些记录将写入当前患者，请对照原句确认说的是本人";
    private String confirmHint = "内容来自健管师粘贴的文字，请对照原句后再写入";
    private boolean labHint;
    private boolean overflow;
    private List<ChatExtractRow> items = new ArrayList<>();
    private List<ChatExtractIgnored> ignored = new ArrayList<>();
    /** 图片里读出的原文。只用于落会话，不进核对卡。 */
    @JsonIgnore
    private String sourceText;
}
