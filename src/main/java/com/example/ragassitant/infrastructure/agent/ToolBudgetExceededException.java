package com.example.ragassitant.infrastructure.agent;

/**
 * 本次问答的工具调用次数已耗尽（{@code agent.max-tool-calls}），用于中断 Spring AI 内部的工具循环。
 *
 * <p>单独成类是为了让熔断器能<b>区分</b>「后端故障」和「业务控制流」：原先抛的是裸
 * {@code IllegalStateException}，套上熔断器后会被当成 LLM 故障计入失败率 ——
 * 用户问了一个需要很多次工具调用的问题，结果把熔断器打开了，后续所有人都走降级。
 *
 * <p>继承 {@link IllegalStateException} 以保持原有语义；
 * 不能是 {@code ToolExecutionException}，那会被官方处理器转成文本回灌给模型，循环停不下来。
 */
public class ToolBudgetExceededException extends IllegalStateException {

    public ToolBudgetExceededException(String message) {
        super(message);
    }
}
