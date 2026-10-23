package com.healix.agent.llm;

import com.healix.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class LlmClient {

    private final ChatModel chatModel;
    private final ChatModel visionChatModel;
    private final DeepSeekChatCompletionsClient deepSeekChatCompletionsClient;

    public LlmClient(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Autowired(required = false) @Qualifier("visionChatModel") ChatModel visionChatModel,
            @Autowired(required = false) DeepSeekChatCompletionsClient deepSeekChatCompletionsClient) {
        this.chatModel = chatModel;
        this.visionChatModel = visionChatModel;
        this.deepSeekChatCompletionsClient = deepSeekChatCompletionsClient;
    }

    @Value("${healix.agent.enabled:false}")
    private boolean enabled;

    @Value("${healix.agent.provider:deepseek}")
    private String provider;

    @Value("${healix.agent.models.chat:deepseek-chat}")
    private String chatModelName;

    @Value("${healix.agent.vision.model:}")
    private String visionModelName;

    /** 为 true 时打印完整 system/user 请求体（含管理方案上下文），仅建议开发环境开启。 */
    @Value("${healix.agent.llm.log-request:false}")
    private boolean logRequest;

    public boolean isEnabled() {
        return enabled;
    }

    public LlmResponse chat(String systemPrompt, String userMessage, List<ChatTurn> history) {
        return chat("chat", systemPrompt, userMessage, history);
    }

    public LlmResponse chat(String scene, String systemPrompt, String userMessage, List<ChatTurn> history) {
        if (!enabled) {
            log.debug("[LLM] skipped scene={} reason=disabled", scene);
            return LlmResponse.disabled();
        }
        long started = System.currentTimeMillis();
        int historySize = history == null ? 0 : history.size();
        log.info(
                "[LLM] start scene={} mode=chat provider={} model={} systemChars={} userChars={} historyTurns={}",
                scene,
                provider,
                chatModelName,
                length(systemPrompt),
                length(userMessage),
                historySize);
        logRequestPayload(scene, "chat", systemPrompt, userMessage, history);
        try {
            List<Message> messages = buildMessages(systemPrompt, userMessage, history);
            logLlmRequest(chatModelName, formatMessagesInput(messages));
            Prompt prompt = new Prompt(messages);
            ChatResponse response = chatModel.call(prompt);
            LlmResponse result = toResponse(response);
            logLlmReturn(chatModelName, result.content());
            logSuccess(scene, "chat", started, result);
            return result;
        } catch (Exception e) {
            logFailure(scene, "chat", started, e);
            return LlmResponse.disabled();
        }
    }

    public LlmResponse streamChat(
            String systemPrompt, String userMessage, List<ChatTurn> history, Consumer<String> onToken) {
        return streamChat("stream", systemPrompt, userMessage, history, onToken);
    }

    public LlmResponse streamChat(
            String scene,
            String systemPrompt,
            String userMessage,
            List<ChatTurn> history,
            Consumer<String> onToken) {
        if (!enabled) {
            log.debug("[LLM] skipped scene={} reason=disabled", scene);
            return LlmResponse.disabled();
        }
        long started = System.currentTimeMillis();
        int historySize = history == null ? 0 : history.size();
        log.info(
                "[LLM] start scene={} mode=stream provider={} model={} systemChars={} userChars={} historyTurns={}",
                scene,
                provider,
                chatModelName,
                length(systemPrompt),
                length(userMessage),
                historySize);
        logRequestPayload(scene, "stream", systemPrompt, userMessage, history);
        StringBuilder full = new StringBuilder();
        int[] promptTokens = new int[1];
        int[] completionTokens = new int[1];
        boolean[] hasUsage = new boolean[1];
        try {
            List<Message> messages = buildMessages(systemPrompt, userMessage, history);
            logLlmRequest(chatModelName, formatMessagesInput(messages));
            Prompt prompt = new Prompt(messages);
            Flux<ChatResponse> flux = chatModel.stream(prompt);
            flux.doOnNext(chunk -> {
                        if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                            promptTokens[0] = (int) chunk.getMetadata().getUsage().getPromptTokens();
                            completionTokens[0] = (int) chunk.getMetadata().getUsage().getCompletionTokens();
                            hasUsage[0] = true;
                        }
                        String text = chunk.getResult().getOutput().getText();
                        if (StringUtils.hasText(text)) {
                            full.append(text);
                            if (onToken != null) {
                                onToken.accept(text);
                            }
                        }
                    })
                    .blockLast();
            LlmResponse result = new LlmResponse(
                    full.isEmpty() ? null : full.toString(),
                    !full.isEmpty(),
                    hasUsage[0] ? promptTokens[0] : null,
                    hasUsage[0] ? completionTokens[0] : null);
            if (!result.fromLlm() || !StringUtils.hasText(result.content())) {
                logLlmReturn(chatModelName, result.content());
                log.warn(
                        "[LLM] empty scene={} mode=stream provider={} model={} elapsedMs={}",
                        scene,
                        provider,
                        chatModelName,
                        System.currentTimeMillis() - started);
                return LlmResponse.disabled();
            }
            logLlmReturn(chatModelName, result.content());
            logSuccess(scene, "stream", started, result);
            return result;
        } catch (Exception e) {
            logFailure(scene, "stream", started, e);
            return LlmResponse.disabled();
        }
    }

    private List<Message> buildMessages(String systemPrompt, String userMessage, List<ChatTurn> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (history != null) {
            for (ChatTurn turn : history) {
                if (StringUtils.hasText(turn.user())) {
                    messages.add(new UserMessage(turn.user()));
                }
                if (StringUtils.hasText(turn.assistant())) {
                    messages.add(new AssistantMessage(turn.assistant()));
                }
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    private static LlmResponse toResponse(ChatResponse response) {
        String content = response.getResult().getOutput().getText();
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = (int) response.getMetadata().getUsage().getPromptTokens();
            completionTokens = (int) response.getMetadata().getUsage().getCompletionTokens();
        }
        return new LlmResponse(content, true, promptTokens, completionTokens);
    }

    public LlmResponse chatWithImage(
            String systemPrompt, String userMessage, String imageBase64, String mimeType) {
        return chatWithImage("vision", systemPrompt, userMessage, imageBase64, mimeType);
    }

    public LlmResponse chatWithImage(
            String scene, String systemPrompt, String userMessage, String imageBase64, String mimeType) {
        return chatWithImages(scene, systemPrompt, userMessage, List.of(new VisionImage(imageBase64, mimeType)));
    }

    /** 多张图放在同一条用户消息里，模型一次看全（如同一餐的多角度照片）。 */
    public LlmResponse chatWithImages(
            String scene, String systemPrompt, String userMessage, List<VisionImage> images) {
        if (!enabled) {
            log.debug("[LLM] skipped scene={} reason=disabled", scene);
            return LlmResponse.disabled();
        }
        if (images == null || images.isEmpty() || images.stream().anyMatch(i -> !StringUtils.hasText(i.base64()))) {
            log.debug("[LLM] skipped scene={} reason=empty_image", scene);
            return LlmResponse.disabled();
        }
        ChatModel model = resolveVisionModel();
        String modelLabel = visionChatModel != null ? visionModelName : chatModelName;
        long started = System.currentTimeMillis();
        log.info(
                "[LLM] start scene={} mode=vision provider={} model={} systemChars={} userChars={} images={} imageBytes={}",
                scene,
                provider,
                modelLabel,
                length(systemPrompt),
                length(userMessage),
                images.size(),
                images.stream().mapToInt(i -> i.base64().length()).sum());
        // 识图不落 base64，只打文本侧 system/user
        logRequestPayload(scene, "vision", systemPrompt, userMessage, List.of());
        try {
            if (deepSeekChatCompletionsClient != null && visionChatModel != null) {
                List<String> dataUrls = images.stream()
                        .map(i -> toImageDataUrl(i.base64(), i.mimeType()))
                        .toList();
                // 请求/返回日志在 DeepSeekChatCompletionsClient 真实 HTTP 调用处打印
                var ds = deepSeekChatCompletionsClient.chatWithImages(scene, systemPrompt, userMessage, dataUrls);
                LlmResponse result = new LlmResponse(
                        ds.content(), ds.hasContent(), ds.promptTokens(), ds.completionTokens());
                if (!StringUtils.hasText(result.content())) {
                    log.warn(
                            "[LLM] empty-content scene={} mode=vision provider={} model={} finishReason={}",
                            scene,
                            provider,
                            modelLabel,
                            ds.finishReason());
                }
                logSuccess(scene, "vision", started, result, modelLabel);
                return result;
            }
            List<Media> medias = new ArrayList<>();
            for (VisionImage image : images) {
                byte[] bytes = Base64.getDecoder().decode(stripDataUrl(image.base64()));
                MimeType mediaType = StringUtils.hasText(image.mimeType())
                        ? MimeTypeUtils.parseMimeType(image.mimeType())
                        : MimeTypeUtils.IMAGE_JPEG;
                medias.add(Media.builder()
                        .mimeType(mediaType)
                        .data(new ByteArrayResource(bytes))
                        .build());
            }
            UserMessage user = UserMessage.builder()
                    .text(userMessage)
                    .media(medias)
                    .build();
            List<Message> messages = List.of(new SystemMessage(systemPrompt), user);
            logLlmRequest(modelLabel, formatVisionInput(systemPrompt, userMessage, images.size()));
            ChatResponse response = model.call(new Prompt(messages));
            LlmResponse result = toResponse(response);
            logLlmReturn(modelLabel, result.content());
            if (!StringUtils.hasText(result.content())) {
                log.warn(
                        "[LLM] empty-content scene={} mode=vision provider={} model={} rawResponse={}",
                        scene,
                        provider,
                        modelLabel,
                        summarizeChatResponse(response));
            }
            logSuccess(scene, "vision", started, result, modelLabel);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logFailure(scene, "vision", started, e, modelLabel);
            throw toVisionBusinessException(e);
        }
    }

    private ChatModel resolveVisionModel() {
        if (visionChatModel != null) {
            return visionChatModel;
        }
        throw new BusinessException(
                "检验单 OCR 需要识图模型。请配置 healix.agent.vision"
                        + "（默认 deepseek-v4-flash-vision-exp，复用 spring.ai.openai.api-key）");
    }

    private static String toImageDataUrl(String imageBase64, String mimeType) {
        String mime = StringUtils.hasText(mimeType) ? mimeType.trim() : "image/jpeg";
        if (imageBase64.startsWith("data:")) {
            return imageBase64;
        }
        return "data:" + mime + ";base64," + stripDataUrl(imageBase64);
    }

    private static String summarizeChatResponse(ChatResponse response) {
        if (response == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("metadata=").append(response.getMetadata());
        if (response.getResult() != null) {
            var generation = response.getResult();
            sb.append(" generation=").append(generation);
            if (generation.getOutput() != null) {
                var output = generation.getOutput();
                sb.append(" outputText=").append(truncate(output.getText(), 500));
                sb.append(" outputMetadata=").append(output.getMetadata());
            }
            if (generation.getMetadata() != null) {
                sb.append(" genMetadata=").append(generation.getMetadata());
            }
        }
        return truncate(sb.toString(), 4000);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...(truncated)";
    }

    private BusinessException toVisionBusinessException(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("does not support image") || message.contains("image")) {
            return new BusinessException(
                    "当前识图模型不支持图片输入，请检查 VISION_MODEL 是否为支持视觉的模型，或更换 VISION_BASE_URL / VISION_API_KEY");
        }
        return new BusinessException("检验单识图失败：" + message);
    }

    /** 真实发往模型前：模型名 + 完整输入（system / history / user）。 */
    private static void logLlmRequest(String model, String input) {
        log.info("请求LLM，模型名称： {},输入：{}", model, input == null ? "" : input);
    }

    /** 模型返回后：模型名 + 完整回复正文。 */
    private static void logLlmReturn(String model, String content) {
        log.info("模型：{},返回:{}", model, content == null ? "" : content);
    }

    private static String formatMessagesInput(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            String role;
            if (msg instanceof SystemMessage) {
                role = "system";
            } else if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AssistantMessage) {
                role = "assistant";
            } else {
                role = msg.getMessageType() == null ? "unknown" : msg.getMessageType().getValue();
            }
            sb.append('[').append(role).append("] ").append(msg.getText() == null ? "" : msg.getText());
        }
        return sb.toString();
    }

    private static String formatVisionInput(String systemPrompt, String userMessage, int imageCount) {
        return "[system] "
                + (systemPrompt == null ? "" : systemPrompt)
                + "\n[user] "
                + (userMessage == null ? "" : userMessage)
                + "\n[images] count="
                + imageCount
                + " (base64 omitted)";
    }

    private void logRequestPayload(
            String scene, String mode, String systemPrompt, String userMessage, List<ChatTurn> history) {
        if (!logRequest) {
            return;
        }
        log.info(
                "[LLM] request scene={} mode={} --- system ---\n{}",
                scene,
                mode,
                systemPrompt == null ? "" : systemPrompt);
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                ChatTurn turn = history.get(i);
                if (StringUtils.hasText(turn.user())) {
                    log.info(
                            "[LLM] request scene={} mode={} --- history[{}].user ---\n{}",
                            scene,
                            mode,
                            i,
                            turn.user());
                }
                if (StringUtils.hasText(turn.assistant())) {
                    log.info(
                            "[LLM] request scene={} mode={} --- history[{}].assistant ---\n{}",
                            scene,
                            mode,
                            i,
                            turn.assistant());
                }
            }
        }
        log.info(
                "[LLM] request scene={} mode={} --- user ---\n{}",
                scene,
                mode,
                userMessage == null ? "" : userMessage);
    }

    private void logResponsePayload(String scene, String mode, LlmResponse result) {
        if (!logRequest || result == null) {
            return;
        }
        log.info(
                "[LLM] response scene={} mode={} --- content ---\n{}",
                scene,
                mode,
                result.content() == null ? "" : result.content());
    }

    private void logSuccess(String scene, String mode, long startedMs, LlmResponse result) {
        logSuccess(scene, mode, startedMs, result, chatModelName);
    }

    private void logSuccess(String scene, String mode, long startedMs, LlmResponse result, String model) {
        log.info(
                "[LLM] success scene={} mode={} provider={} model={} elapsedMs={} responseChars={} promptTokens={} completionTokens={}",
                scene,
                mode,
                provider,
                model,
                System.currentTimeMillis() - startedMs,
                length(result.content()),
                result.promptTokens(),
                result.completionTokens());
        logResponsePayload(scene, mode, result);
    }

    private void logFailure(String scene, String mode, long startedMs, Exception e) {
        logFailure(scene, mode, startedMs, e, chatModelName);
    }

    private void logFailure(String scene, String mode, long startedMs, Exception e, String model) {
        log.warn(
                "[LLM] failed scene={} mode={} provider={} model={} elapsedMs={} error={}",
                scene,
                mode,
                provider,
                model,
                System.currentTimeMillis() - startedMs,
                e.getMessage(),
                e);
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static String stripDataUrl(String base64) {
        int comma = base64.indexOf(',');
        return comma >= 0 ? base64.substring(comma + 1) : base64;
    }

    public record ChatTurn(String user, String assistant) {}

    public record VisionImage(String base64, String mimeType) {}
}
