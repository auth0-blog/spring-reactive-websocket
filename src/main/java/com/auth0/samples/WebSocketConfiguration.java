package com.auth0.samples;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
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
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


@Configuration
public class WebSocketConfiguration {
    private static final String REDIS_URL = "redis://localhost";
    private static final String REDIS_MESSAGING_CHANNEL = "messaging-channel";
    private static final RSAPrivateKey NULL_PRIVATE_KEY = null;
    private final Map<String, MessageHandler> connections = new ConcurrentHashMap<>();

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create(REDIS_URL);
    }

    @Bean
    public IntegrationFlow fileFlow(PublishSubscribeChannel channel,
                                    RedisPubSubReactiveCommands<String, String> reactiveConnection) {

        reactiveConnection.subscribe(REDIS_MESSAGING_CHANNEL).subscribe();
        Publisher<Message<?>> p = reactiveConnection.observeChannels()
                .map(channelMessage -> new GenericMessage<>(channelMessage.getMessage()));
        return IntegrationFlows.from(p).channel(channel).get();
    }

    @Bean
    public RedisPubSubReactiveCommands<String, String> reactiveConnection(RedisClient redisClient) {
        StatefulRedisPubSubConnection<String, String> connection = redisClient.connectPubSub();
        return connection.reactive();
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
    public WebSocketHandler webSocketHandler(PublishSubscribeChannel channel,
                                             RedisPubSubReactiveCommands<String, String> reactiveConnection,
                                             RedisClient redisClient) {
        return session -> {
            String queryParams = session.getHandshakeInfo().getUri().getQuery();
             if (queryParams == null) {
                return closeUnauthenticatedSession(session);
            }
            String token = queryParams.replace("token=", "");
            if (token.isEmpty()) {
                return closeUnauthenticatedSession(session);
            }

            DecodedJWT jwt = JWT.decode(token);
            String kid = jwt.getKeyId();
            JwkProvider provider = new UrlJwkProvider("https://bkrebs.auth0.com/");

            try {
                Jwk jwk = provider.get(kid);
                Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), NULL_PRIVATE_KEY);
                JWTVerifier verifier = JWT.require(algorithm).build();
                verifier.verify(token);
            } catch (JwkException | SignatureVerificationException e) {
                return closeUnauthenticatedSession(session);
            }

            Flux<WebSocketMessage> publisher = Flux.create((Consumer<FluxSink<WebSocketMessage>>) fluxSink -> {
                connections.put(session.getId(), new ForwardingMessageHandler(session, fluxSink));
                channel.subscribe(connections.get(session.getId()));
            }).doFinally(signalType -> {
                channel.unsubscribe(connections.get(session.getId()));
                connections.remove(session.getId());
            });

            session.receive().flatMap(webSocketMessage -> {
                StatefulRedisPubSubConnection<String, String> connection = redisClient.connectPubSub();
                RedisPubSubReactiveCommands<String, String> co = connection.reactive();
                co.publish(REDIS_MESSAGING_CHANNEL, webSocketMessage.getPayloadAsText())
                        .subscribe();
                return Mono.just(webSocketMessage);
            }).subscribe();
            return session.send(publisher);
        };
    }

    private Mono<Void> closeUnauthenticatedSession(WebSocketSession session) {
        return session.receive().then(Mono.create(monoSink -> {
            session.close(CloseStatus.POLICY_VIOLATION);
        }));
    }

    @Bean
    public HandlerMapping handlerMapping(WebSocketHandler webSocketHandler) {
        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(10);
        handlerMapping.setUrlMap(Collections.singletonMap("/ws/messages", webSocketHandler));
        return handlerMapping;
    }
}
