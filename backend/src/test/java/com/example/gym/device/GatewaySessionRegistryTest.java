package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class GatewaySessionRegistryTest {

    @Test
    void commandsAndRepliesSentAtTheSameTimeNeverWriteConcurrently() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        doAnswer(invocation -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            Thread.sleep(20);
            inside.decrementAndGet();
            written.incrementAndGet();
            return null;
        }).when(session).sendMessage(any(WebSocketMessage.class));

        GatewaySessionRegistry registry = new GatewaySessionRegistry(ignored -> true);
        registry.register("gw-1", session);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] sends = new Future<?>[8];
        for (int i = 0; i < sends.length; i++) {
            boolean command = i % 2 == 0;
            sends[i] = pool.submit(() -> {
                start.await();
                return command ? registry.send("gw-1", "command") : registry.reply(session, "reply");
            });
        }
        start.countDown();
        for (Future<?> f : sends) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo(true);
        }
        pool.shutdown();

        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(written.get()).isEqualTo(8);
    }

    @Test
    void invalidatedSessionReceivesNoCommand() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        java.util.concurrent.atomic.AtomicBoolean allow = new java.util.concurrent.atomic.AtomicBoolean(true);
        GatewaySessionRegistry registry = new GatewaySessionRegistry(ignored -> allow.get());
        registry.register("gw-1", session);

        assertThat(registry.send("gw-1", "command")).isTrue();
        verify(session, times(1)).sendMessage(any(WebSocketMessage.class));

        allow.set(false);
        assertThat(registry.send("gw-1", "later-command")).isFalse();
        assertThat(registry.isOnline("gw-1")).isFalse();
        verify(session, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(session, atLeastOnce()).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
    }
}
