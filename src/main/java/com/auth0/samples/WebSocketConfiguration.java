package com.auth0.samples;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


@Configuration
public class WebSocketConfiguration {
    private static final String REDIS_URL = "redis://localhost";
    private static final String REDIS_MESSAGING_CHANNEL = "messaging-channel";
    private final Map<String, MessageHandler> connections = new ConcurrentHashMap<>();

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create(REDIS_URL);
    }

    @Bean
    public IntegrationFlow fileFlow(PublishSubscribeChannel channel, RedisClient redisClient) {
        StatefulRedisPubSubConnection<String, String> connection = redisClient.connectPubSub();
        RedisPubSubReactiveCommands<String, String> reactive = connection.reactive();
        reactive.subscribe(REDIS_MESSAGING_CHANNEL).subscribe();

        Publisher<Message<?>> p = reactive.observeChannels()
                .map(channelMessage -> new GenericMessage<>(channelMessage.getMessage()));
        return IntegrationFlows.from(p).channel(channel).get();
    }

    @Bean
    @Primary
    public PublishSubscribeChannel pubSubChannel() {
        return new PublishSubscribeChannel();
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public WebSocketHandler webSocketHandler(PublishSubscribeChannel channel) {
        return session -> {
            Flux<WebSocketMessage> publisher = Flux.create((Consumer<FluxSink<WebSocketMessage>>) fluxSink -> {
                connections.put(session.getId(), new ForwardingMessageHandler(session, fluxSink));
                channel.subscribe(connections.get(session.getId()));
            }).doFinally(signalType -> {
                channel.unsubscribe(connections.get(session.getId()));
                connections.remove(session.getId());
            });
            return session.send(publisher);
        };
    }

    @Bean
    public HandlerMapping handlerMapping(WebSocketHandler webSocketHandler) {
        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(10);
        handlerMapping.setUrlMap(Collections.singletonMap("/ws/messages", webSocketHandler));
        return handlerMapping;
    }
}
