package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.model.ToolCall;

/**
 * 工具执行端口。
 *
 * <p>只保留 {@link #invoke(ToolCall)} 一个方法：原先还有 {@code listToolNames()} /
 * {@code getToolDescriptions()} / {@code validateArguments()} 三个方法，
 * 它们是手写 Agent 循环时代的产物（把工具清单拼进提示词、调用前单独校验一次），
 * 迁到 Spring AI 2.0 原生 Tool Calling 之后全部零调用方。
 *
 * <p>参数校验没有删掉，而是收进 {@code invoke} 内部 —— 让「校验」和「执行」共用同一个入口，
 * 不会再出现「有校验方法但没人调」的状态（原实现的 {@code validateArguments} 恒返回 null，
 * 注释指向 ToolCallValidator，而那个类当时已经没有任何调用方了）。
 */
public interface ToolRegistryPort {

    /**
     * 执行一次工具调用。
     *
     * <p>实现方需自行完成参数校验，并把校验失败信息<b>作为返回值</b>返回（而不是抛异常）——
     * 返回值会被直接喂给 LLM，让它据此纠正参数或向用户追问缺失信息。
     */
    String invoke(ToolCall toolCall);
}
