package com.example.ragassitant.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 流式输出通道：内部用 Reactor {@link Sinks.Many} 承载事件，对外暴露
 * {@link #flux()} 交给 Controller 直接返回。
 *
 * <p>【为什么替换 SseEmitter】旧实现是「worker 线程阻塞执行 → SseEmitter.send 推送」，
 * 存在两个问题：
 * <ol>
 *   <li>application 层直接依赖 spring-webmvc 的 {@code SseEmitter}，分层不干净；</li>
 *   <li>超时/断连只能靠 onTimeout/onError 回调翻转标志位，时序上可能漏事件。</li>
 * </ol>
 * 现在 worker 线程只管往 Sink {@code tryEmit}，Controller 返回 {@code Flux<ServerSentEvent>}
 * （Spring MVC 原生支持 Reactive 返回值，不需要 WebFlux 依赖）；客户端断开/异步超时会触发
 * 订阅取消，{@code doOnCancel} 把 finished 置真，worker 侧所有 emit 随即短路返回。
 *
 * <p>线程模型：Sink 为 unicast + 无界缓冲（单订阅者 = 单个 HTTP 响应），
 * 允许 worker 在 Controller 尚未订阅前先产事件；数据经 Jackson 序列化为 JSON 字符串，
 * 与原 SseEmitter 的线上报文格式完全一致，前端零改动。
 */
public final class StreamChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Sinks.Many<ServerSentEvent<String>> sink =
            Sinks.many().unicast().onBackpressureBuffer(new ConcurrentLinkedQueue<>());

    private final AtomicBoolean finished = new AtomicBoolean(false);

    public static StreamChannel open() {
        return new StreamChannel();
    }

    private StreamChannel() {}

    /** 客户端是否已不可达（断连/超时/已收尾），为 true 时 worker 应尽快退出。 */
    public boolean isFinished() {
        return finished.get();
    }

    /**
     * 推送一个命名事件。data 序列化为 JSON —— 与原 SseEmitter 的
     * MappingJackson2HttpMessageConverter 输出格式一致，前端 JSON.parse 行为不变。
     */
    public void emit(String eventName, Object data) {
        if (finished.get()) return;
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            json = String.valueOf(data);
        }
        Sinks.EmitResult result =
                sink.tryEmitNext(ServerSentEvent.<String>builder(json).event(eventName).build());
        if (result.isFailure()) {
            // 订阅者已不存在或 Sink 已终止：标记结束，让 worker 循环条件自然退出
            finished.set(true);
        }
    }

    /** 推送 error 事件并终止流（幂等）。 */
    public void emitError(String message) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            sink.tryEmitNext(ServerSentEvent.<String>builder(
                            MAPPER.writeValueAsString(Map.of("message", message)))
                    .event("error").build());
        } catch (Exception ignored) {}
        sink.tryEmitComplete();
    }

    /** 正常收尾并终止流（幂等）。 */
    public void complete() {
        if (!finished.compareAndSet(false, true)) return;
        sink.tryEmitComplete();
    }

    /** 交给 Controller 直接作为 HTTP 响应体返回的事件流。 */
    public Flux<ServerSentEvent<String>> flux() {
        return sink.asFlux()
                // 断连 / 异步超时时由框架取消订阅，这里把状态回传给 worker
                .doOnCancel(() -> finished.set(true));
    }
}
