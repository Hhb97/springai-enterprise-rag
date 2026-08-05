package com.example.ragassitant.application.service;

import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.TextToolCallParser;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.example.ragassitant.infrastructure.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ReActAgentAppService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SMALL_TALK_FALLBACK =
            "您好！我是基于 ReAct 推理模式的智能运维助手，可以帮您查询设备清单与详情、分析能耗数据，也能基于知识库回答业务问题。请问需要什么帮助？";

    private final LlmPort llmPort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final PromptBuilder promptBuilder;
    private final RagAppService ragAppService;
    private final QueryRewriteService queryRewriteService;
    private final ToolCatalog toolCatalog;
    private final TextToolCallParser textToolCallParser;
    private final AgentProperties props;

    public ReActAgentAppService(LlmPort llmPort,
                                 ConversationStorePort conversationStorePort,
                                 ToolRegistryPort toolRegistry,
                                 PromptBuilder promptBuilder,
                                 RagAppService ragAppService,
                                 QueryRewriteService queryRewriteService,
                                 ToolCatalog toolCatalog,
                                 TextToolCallParser textToolCallParser,
                                 AgentProperties props) {
        this.llmPort = llmPort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.ragAppService = ragAppService;
        this.queryRewriteService = queryRewriteService;
        this.toolCatalog = toolCatalog;
        this.textToolCallParser = textToolCallParser;
        this.props = props;
        log.info("🧠 ReActAgentAppService 初始化完成（手动 ReAct 循环，maxSteps={}）", props.getMaxToolCalls());
    }

    public Map<String, Object> chat(String question, String sessionId,
                                     boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        MDC.put(TraceIdFilter.SESSION_ID_KEY, sessionId == null ? "" : sessionId);
        try {
            return doChat(question, sessionId, useRag, enableHybridSearch, customTopK);
        } finally {
            MDC.remove(TraceIdFilter.SESSION_ID_KEY);
        }
    }

    private Map<String, Object> doChat(String question, String sessionId, boolean useRag,
                                        boolean enableHybridSearch, Integer customTopK) {
        long totalStart = System.currentTimeMillis();
        log.info("🧠 ReAct 处理问题: sessionId={}, useRag={}, question={}", sessionId, useRag, question);

        List<Map.Entry<String, String>> history = conversationStorePort.getHistory(
                sessionId, props.getMemorySize());

        if (isSmallTalk(question)) {
            return handleSmallTalk(question, sessionId);
        }

        String retrievalQuery = question;
        boolean queryRewritten = false;
        String rewrittenQuery = null;
        long rewriteMs = 0;

        if (!history.isEmpty() && queryRewriteService.isEnabled()) {
            long rwStart = System.currentTimeMillis();
            List<String> rewrites = queryRewriteService.rewrite(question, history, sessionId);
            rewriteMs = System.currentTimeMillis() - rwStart;
            if (rewrites != null && !rewrites.isEmpty()) {
                rewrittenQuery = rewrites.get(0);
                queryRewritten = !rewrittenQuery.equals(question);
                if (queryRewritten) {
                    retrievalQuery = rewrittenQuery;
                    log.info("🧠 ReAct Query 改写: '{}' → '{}'", question, rewrittenQuery);
                }
            }
        }

        List<DocumentChunk> docs = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        String systemPrompt;

        if (useRag) {
            try {
                int topK = customTopK != null ? customTopK : 5;
                docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, topK);
                for (DocumentChunk doc : docs) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("content", doc.getText());
                    Map<String, Object> meta = doc.getMetadata();
                    Object title = meta != null ? meta.get("title") : null;
                    Object fileName = meta != null ? meta.get("fileName") : null;
                    source.put("metadata", (title != null ? title : (fileName != null ? fileName : "文档")));
                    source.put("score", doc.getScore());
                    sources.add(source);
                }
            } catch (Exception e) {
                log.warn("⚠️ ReAct RAG 检索失败，已跳过: {}", e.getMessage());
            }
        }

        systemPrompt = promptBuilder.buildReActSystemPrompt(toolCatalog.renderForPrompt(), docs);

        List<MessageDTO> messages = buildBaseMessages(history, systemPrompt);
        messages.add(new MessageDTO(Role.USER, question));

        int maxSteps = props.getMaxToolCalls();
        ReActResult result = runReActLoop(messages, sessionId, maxSteps);

        String answer = result.finalAnswer();
        List<Map<String, Object>> stepTraces = result.stepTraces();

        conversationStorePort.saveUser(sessionId, question);
        conversationStorePort.saveAssistant(sessionId, answer);

        long totalMs = System.currentTimeMillis() - totalStart;

        Map<String, Object> response = new HashMap<>();
        response.put("answer", answer);
        response.put("sources", sources);
        response.put("mode", "react");
        response.put("queryRewritten", queryRewritten);
        response.put("rewrittenQuery", rewrittenQuery);
        response.put("rewriteMs", rewriteMs);
        response.put("totalMs", totalMs);
        response.put("steps", stepTraces);
        response.put("stepCount", stepTraces.size());
        response.put("sessionId", sessionId);

        log.info("🧠 ReAct 完成: {} steps, {}ms, answer length={}", stepTraces.size(), totalMs, answer.length());
        return response;
    }

    private Map<String, Object> handleSmallTalk(String question, String sessionId) {
        log.info("💬 ReAct 闲聊短路");
        List<MessageDTO> messages = List.of(
                new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                new MessageDTO(Role.USER, question));

        long start = System.currentTimeMillis();
        String reply = llmPort.chatWithRawMessages(messages);
        long elapsed = System.currentTimeMillis() - start;

        String answer = (reply == null || reply.isBlank()) ? SMALL_TALK_FALLBACK : cleanupAnswer(reply);
        if (answer.isBlank()) answer = SMALL_TALK_FALLBACK;

        conversationStorePort.saveUser(sessionId, question);
        conversationStorePort.saveAssistant(sessionId, answer);

        Map<String, Object> response = new HashMap<>();
        response.put("answer", answer);
        response.put("sources", List.of());
        response.put("mode", "react");
        response.put("totalMs", elapsed);
        response.put("steps", List.of());
        response.put("stepCount", 0);

        return response;
    }

    private ReActResult runReActLoop(List<MessageDTO> messages, String sessionId, int maxSteps) {
        List<Map<String, Object>> stepTraces = new ArrayList<>();
        List<MessageDTO> currentMessages = new ArrayList<>(messages);
        String finalAnswer = null;

        for (int step = 1; step <= maxSteps; step++) {
            long stepStart = System.currentTimeMillis();
            log.info("🧠 ReAct step {}: sending {} messages to LLM", step, currentMessages.size());

            String llmResponse;
            try {
                llmResponse = llmPort.chatWithRawMessages(currentMessages);
            } catch (Exception e) {
                log.error("🧠 ReAct step {} failed: {}", step, e.getMessage());
                stepTraces.add(buildStepTrace(step, "LLM 调用出错", null, null, e.getMessage(), System.currentTimeMillis() - stepStart));
                finalAnswer = "推理步骤 " + step + " 出错: " + e.getMessage();
                break;
            }

            if (llmResponse == null || llmResponse.isBlank()) {
                stepTraces.add(buildStepTrace(step, "模型返回空响应", null, null, "模型返回空", System.currentTimeMillis() - stepStart));
                finalAnswer = "模型返回空响应，请重试。";
                break;
            }

            StepDecision decision = parseReActResponse(llmResponse);
            long stepMs = System.currentTimeMillis() - stepStart;

            log.info("🧠 ReAct step {}: thought='{}' tool={} final={}",
                    step, truncate(decision.thought(), 80),
                    decision.toolCall() != null ? decision.toolCall().name : "none",
                    decision.isFinalAnswer());

            if (decision.isFinalAnswer()) {
                stepTraces.add(buildStepTrace(step, decision.thought(), null, decision.answer(), null, stepMs));
                finalAnswer = decision.answer();
                break;
            }

            if (decision.toolCall() == null) {
                stepTraces.add(buildStepTrace(step, decision.thought(), null, null, "未识别到工具调用或最终答案", stepMs));
                finalAnswer = (decision.answer() != null && !decision.answer().isBlank())
                        ? decision.answer()
                        : (decision.thought() != null ? decision.thought() : cleanupAnswer(llmResponse));
                break;
            }

            String observation;
            try {
                observation = toolRegistry.invoke(decision.toolCall());
            } catch (Exception e) {
                log.error("🧠 ReAct step {} tool execution failed: {}", step, e.getMessage());
                observation = "工具执行失败: " + e.getMessage();
            }

            stepTraces.add(buildStepTrace(step, decision.thought(), decision.toolCall(), observation, null, stepMs));

            currentMessages.add(new MessageDTO(Role.ASSISTANT, llmResponse));
            String observationPrompt = "【Observation 工具执行结果】\n```\n" + observation + "\n```\n\n";
            if (step == maxSteps) {
                observationPrompt += "已达到最大步数，请直接基于以上结果用中文给出最终回答，不要再调用任何工具。";
            } else if (step == maxSteps - 1) {
                observationPrompt += "剩余 1 步，信息足够就直接给出最终回答，信息不足再调用最后一次工具。";
            } else {
                observationPrompt += "请继续：信息足够就给出最终回答（tool=null 并填 answer），否则继续调用下一个工具。";
            }
            currentMessages.add(new MessageDTO(Role.USER, observationPrompt));
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = "已达到最大推理步数 (" + maxSteps + ")，未能得出最终答案。请尝试更具体的问题，或提供更多参数。";
        }

        return new ReActResult(finalAnswer, stepTraces);
    }

    private StepDecision parseReActResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new StepDecision("", null, true, "模型未返回有效内容");
        }

        List<ToolCall> parsedCalls = textToolCallParser.parse(llmResponse);

        if (parsedCalls != null && !parsedCalls.isEmpty()) {
            StringBuilder thought = new StringBuilder();
            String cleaned = llmResponse.replace("```json", "").replace("```", "").trim();
            int jsonStart = cleaned.indexOf('[');
            if (jsonStart < 0) jsonStart = cleaned.indexOf('{');
            if (jsonStart > 0) {
                thought.append(cleaned, 0, jsonStart).trimToSize();
            }
            if (thought.length() == 0) {
                thought.append("需要调用工具: ").append(parsedCalls.get(0).name);
            }
            return new StepDecision(thought.toString(), parsedCalls.get(0), false, null);
        }

        Map<String, Object> structured = parseStructuredJson(llmResponse);
        if (structured != null) {
            String thought = getString(structured, "thought");
            String toolName = getString(structured, "tool");
            Object argsObj = structured.get("arguments");
            String answer = getString(structured, "answer");

            if (toolName != null && !toolName.isBlank() && !"null".equalsIgnoreCase(toolName)) {
                Map<String, Object> args = new HashMap<>();
                if (argsObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) argsObj;
                    args = map;
                }
                return new StepDecision(thought, new ToolCall(toolName, args), false, null);
            }

            if (answer != null && !answer.isBlank()) {
                return new StepDecision(thought, null, true, answer);
            }

            if (thought != null && !thought.isBlank()) {
                return new StepDecision(thought, null, true, thought);
            }
        }

        return new StepDecision("", null, true, cleanupAnswer(llmResponse));
    }

    private Map<String, Object> parseStructuredJson(String text) {
        try {
            String cleaned = text.replace("```json", "").replace("```", "").trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = cleaned.substring(start, end + 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = MAPPER.readValue(json, Map.class);
                return map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private List<MessageDTO> buildBaseMessages(List<Map.Entry<String, String>> history, String systemPrompt) {
        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, systemPrompt));
        for (Map.Entry<String, String> pair : history) {
            messages.add(new MessageDTO(Role.USER, pair.getKey()));
            messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
        }
        return messages;
    }

    private Map<String, Object> buildStepTrace(int step, String thought, ToolCall action,
                                                String observation, String errorMsg, long durationMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("step", step);
        trace.put("thought", thought);
        trace.put("tool", action != null ? action.name : null);
        trace.put("arguments", action != null ? action.arguments : null);
        trace.put("observation", observation);
        trace.put("error", errorMsg);
        trace.put("durationMs", durationMs);
        return trace;
    }

    private String cleanupAnswer(String answer) {
        if (answer == null) return "";
        String cleaned = answer.replaceAll("(?s)\\[Tools?\\].*?(?=\\n(?:## |### |回答：|基于|综上|因此|总结|设备|根据)|$)", "");
        cleaned = cleaned.replaceAll("(?s)\\{\\s*\"name\"\\s*:.*?\"arguments\"\\s*:.*?\\}", "");
        cleaned = cleaned.replaceAll("(?s)\\[\\s*\\{\\s*\"name\".*?\\}\\s*\\]", "");
        cleaned = cleaned.replace("```json", "").replace("```", "");
        cleaned = cleaned.replaceAll("^Thought[：:].*$", "");
        cleaned = cleaned.replaceAll("^Action[：:].*$", "");
        cleaned = cleaned.replaceAll("^Observation[：:].*$", "");
        cleaned = cleaned.replaceAll("^Answer[：:].*$", "");
        return cleaned.trim();
    }

    private boolean isSmallTalk(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.trim().toLowerCase();
        return SMALL_TALK_PATTERN.matcher(lower).matches();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final Pattern SMALL_TALK_PATTERN = Pattern.compile(
            "^(你好|您好|hi|hello|hey|thanks|谢谢|再见|拜拜|bye|good\\s*morning|早上好|晚安|good\\s*night|你是谁|你叫什么|介绍一下自己)$"
    );

    private record StepDecision(String thought, ToolCall toolCall, boolean isFinalAnswer, String answer) {}

    private record ReActResult(String finalAnswer, List<Map<String, Object>> stepTraces) {}
}