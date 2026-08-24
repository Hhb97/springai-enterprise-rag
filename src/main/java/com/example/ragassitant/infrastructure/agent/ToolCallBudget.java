package com.example.ragassitant.infrastructure.agent;

/**
 * 原生 Tool Calling 的调用预算（ThreadLocal 实现）。
 *
 * <p>【为什么需要它】Spring AI 2.0.0 GA 的 {@code DefaultToolCallingManager.Builder}
 * 还没有 maxCallsPerTool / maxTotalToolCalls（官方在后续版本才加入 Tool Call Limits），
 * {@code agent.max-tool-calls} 此前只被拿来打日志、从未真正限制过 ChatClient 内部的
 * 工具循环。既然所有原生工具执行都经过 {@link TracingToolCallback} 装饰器，
 * 就在装饰器层做预算控制：请求开始时 {@link #begin} 存入额度，每次工具执行
 * {@link #tryAcquire} 扣减，耗尽即抛 IllegalStateException 中断循环 —— 注意不能抛
 * {@code ToolExecutionException}，那会被官方 ToolExecutionExceptionProcessor 转成
 * 错误文本回灌给模型继续跑，等于没限。
 *
 * <p>用 ThreadLocal 是因为 ChatClient.call() 的整个 Advisor 循环在同一线程上执行；
 * 每次请求入口都会 begin 重置，不存在跨请求污染。
 */
public final class ToolCallBudget {

    private static final ThreadLocal<Integer> REMAINING = new ThreadLocal<>();

    private ToolCallBudget() {}

    /** 请求开始时设置本次允许的工具执行次数。 */
    public static void begin(int maxCalls) {
        REMAINING.set(maxCalls <= 0 ? Integer.MAX_VALUE : maxCalls);
    }

    /** 请求结束清理，防止线程池复用线程时残留状态。 */
    public static void clear() {
        REMAINING.remove();
    }

    /**
     * 尝试消耗一次额度。额度未初始化（如 FORCE_EXEC 等不走 ChatClient 循环的路径）
     * 视为不限次，返回 true。
     */
    public static boolean tryAcquire() {
        Integer remaining = REMAINING.get();
        if (remaining == null) return true;
        if (remaining <= 0) return false;
        REMAINING.set(remaining - 1);
        return true;
    }
}
