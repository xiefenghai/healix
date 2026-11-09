package com.healix.agent.llm;

import com.healix.common.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * LLM 门面：文本 chat/stream 走 langchain4j；识图走 DeepSeek HTTP（关 thinking）。
 */
@Slf4j
@Service
public class LlmClient {

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final DeepSeekChatCompletionsClient deepSeekChatCompletionsClient;

    public LlmClient(
            @Autowired(required = false) @Qualifier("healixChatModel") ChatLanguageModel chatModel,
            @Autowired(required = false) @Qualifier("healixStreamingChatModel")
                    StreamingChatLanguageModel streamingChatModel,
            @Autowired(required = false) DeepSeekChatCompletionsClient deepSeekChatCompletionsClient) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
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
        return enabled && chatModel != null;
    }

    public LlmResponse chat(String systemPrompt, String userMessage, List<ChatTurn> history) {
        return chat("chat", systemPrompt, userMessage, history);
    }

    public LlmResponse chat(String scene, String systemPrompt, String userMessage, List<ChatTurn> history) {
        if (!enabled || chatModel == null) {
            log.debug("[LLM] skipped scene={} reason={}", scene, enabled ? "no_model" : "disabled");
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
            List<ChatMessage> messages = buildMessages(systemPrompt, userMessage, history);
            logLlmRequest(chatModelName, formatMessagesInput(messages));
            Response<AiMessage> response = chatModel.generate(messages);
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
        if (!enabled || streamingChatModel == null) {
            // 无流式 bean 时回落同步 chat，再一次性回调
            if (enabled && chatModel != null) {
                LlmResponse once = chat(scene, systemPrompt, userMessage, history);
                if (once.fromLlm() && StringUtils.hasText(once.content()) && onToken != null) {
                    onToken.accept(once.content());
                }
                return once;
            }
            log.debug("[LLM] skipped scene={} reason={}", scene, enabled ? "no_stream_model" : "disabled");
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
        AtomicReference<TokenUsage> usageRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            List<ChatMessage> messages = buildMessages(systemPrompt, userMessage, history);
            logLlmRequest(chatModelName, formatMessagesInput(messages));
            streamingChatModel.generate(
                    messages,
                    new StreamingResponseHandler<>() {
                        @Override
                        public void onNext(String token) {
                            if (StringUtils.hasText(token)) {
                                full.append(token);
                                if (onToken != null) {
                                    onToken.accept(token);
                                }
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
                            if (response != null) {
                                usageRef.set(response.tokenUsage());
                                if (full.isEmpty()
                                        && response.content() != null
                                        && StringUtils.hasText(response.content().text())) {
                                    full.append(response.content().text());
                                }
                            }
                            done.countDown();
                        }

                        @Override
                        public void onError(Throwable error) {
                            errorRef.set(error);
                            done.countDown();
                        }
                    });
            if (!done.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("LLM stream timeout");
            }
            if (errorRef.get() != null) {
                throw new IllegalStateException(errorRef.get().getMessage(), errorRef.get());
            }
            TokenUsage usage = usageRef.get();
            LlmResponse result = new LlmResponse(
                    full.isEmpty() ? null : full.toString(),
                    !full.isEmpty(),
                    usage == null ? null : usage.inputTokenCount(),
                    usage == null ? null : usage.outputTokenCount());
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

    private List<ChatMessage> buildMessages(String systemPrompt, String userMessage, List<ChatTurn> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt == null ? "" : systemPrompt));
        if (history != null) {
            for (ChatTurn turn : history) {
                if (StringUtils.hasText(turn.user())) {
                    messages.add(UserMessage.from(turn.user()));
                }
                if (StringUtils.hasText(turn.assistant())) {
                    messages.add(AiMessage.from(turn.assistant()));
                }
            }
        }
        messages.add(UserMessage.from(userMessage == null ? "" : userMessage));
        return messages;
    }

    private static LlmResponse toResponse(Response<AiMessage> response) {
        String content =
                response == null || response.content() == null ? null : response.content().text();
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response != null && response.tokenUsage() != null) {
            promptTokens = response.tokenUsage().inputTokenCount();
            completionTokens = response.tokenUsage().outputTokenCount();
        }
        return new LlmResponse(content, StringUtils.hasText(content), promptTokens, completionTokens);
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
        if (deepSeekChatCompletionsClient == null) {
            throw new BusinessException(
                    "检验单 OCR 需要识图模型。请配置 healix.agent.vision"
                            + "（默认 deepseek-v4-flash-vision-exp，复用 DEEPSEEK_API_KEY / healix.agent.api-key）");
        }
        String modelLabel = StringUtils.hasText(visionModelName) ? visionModelName : chatModelName;
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
        logRequestPayload(scene, "vision", systemPrompt, userMessage, List.of());
        try {
            List<String> dataUrls = images.stream()
                    .map(i -> toImageDataUrl(i.base64(), i.mimeType()))
                    .toList();
            var ds = deepSeekChatCompletionsClient.chatWithImages(scene, systemPrompt, userMessage, dataUrls);
            LlmResponse result =
                    new LlmResponse(ds.content(), ds.hasContent(), ds.promptTokens(), ds.completionTokens());
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
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logFailure(scene, "vision", started, e, modelLabel);
            throw toVisionBusinessException(e);
        }
    }

    private static String toImageDataUrl(String imageBase64, String mimeType) {
        String mime = StringUtils.hasText(mimeType) ? mimeType.trim() : "image/jpeg";
        if (imageBase64.startsWith("data:")) {
            return imageBase64;
        }
        return "data:" + mime + ";base64," + stripDataUrl(imageBase64);
    }

    private BusinessException toVisionBusinessException(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("does not support image") || message.contains("image")) {
            return new BusinessException(
                    "当前识图模型不支持图片输入，请检查 VISION_MODEL 是否为支持视觉的模型，或更换 VISION_BASE_URL / VISION_API_KEY");
        }
        return new BusinessException("检验单识图失败：" + message);
    }

    private static void logLlmRequest(String model, String input) {
        log.info("请求LLM，模型名称： {},输入：{}", model, input == null ? "" : input);
    }

    private static void logLlmReturn(String model, String content) {
        log.info("模型：{},返回:{}", model, content == null ? "" : content);
    }

    private static String formatMessagesInput(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            String role;
            String text;
            if (msg instanceof SystemMessage sm) {
                role = "system";
                text = sm.text();
            } else if (msg instanceof UserMessage um) {
                role = "user";
                text = um.singleText();
            } else if (msg instanceof AiMessage am) {
                role = "assistant";
                text = am.text();
            } else {
                role = "unknown";
                text = String.valueOf(msg);
            }
            sb.append('[').append(role).append("] ").append(text == null ? "" : text);
        }
        return sb.toString();
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
