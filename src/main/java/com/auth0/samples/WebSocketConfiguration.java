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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
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

import static com.auth0.samples.RedisConfiguration.REDIS_MESSAGING_CHANNEL;


@Configuration
public class WebSocketConfiguration {
    private static final RSAPrivateKey NULL_PRIVATE_KEY = null;
    private static final String AUTH_DOMAIN = "https://bkrebs.auth0.com/";
    private final Map<String, MessageHandler> connections = new ConcurrentHashMap<>();

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public HandlerMapping handlerMapping(WebSocketHandler webSocketHandler) {
        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(10);
        handlerMapping.setUrlMap(Collections.singletonMap("/ws/messages", webSocketHandler));
        return handlerMapping;
    }

    @Bean
    public WebSocketHandler webSocketHandler(PublishSubscribeChannel channel, RedisClient redisClient) {
        return session -> {
            if (!authenticateHandshake(session.getHandshakeInfo())) {
                return session.receive().then(Mono.create(monoSink -> {
                    session.close(CloseStatus.POLICY_VIOLATION);
                }));
            }

            Flux<WebSocketMessage> publisher = Flux.create((Consumer<FluxSink<WebSocketMessage>>) fluxSink -> {
                connections.put(session.getId(), new ForwardingMessageHandler(session, fluxSink));
                channel.subscribe(connections.get(session.getId()));
            }).doFinally(signalType -> {
                channel.unsubscribe(connections.get(session.getId()));
                connections.remove(session.getId());
            });

            session.receive().flatMap(webSocketMessage -> {
                redisClient.connectPubSub()
                        .reactive()
                        .publish(REDIS_MESSAGING_CHANNEL, webSocketMessage.getPayloadAsText())
                        .subscribe();
                return Mono.just(webSocketMessage);
            }).subscribe();
            return session.send(publisher);
        };
    }

    private boolean authenticateHandshake(HandshakeInfo handshake) {
        String queryParams = handshake.getUri().getQuery();
        if (queryParams == null) {
            return false;
       }
        String token = queryParams.replace("token=", "");
        if (token.isEmpty()) {
            return false;
        }

        DecodedJWT jwt = JWT.decode(token);
        String kid = jwt.getKeyId();
        JwkProvider provider = new UrlJwkProvider(AUTH_DOMAIN);

        try {
            Jwk jwk = provider.get(kid);
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), NULL_PRIVATE_KEY);
            JWTVerifier verifier = JWT.require(algorithm).build();
            verifier.verify(token);
        } catch (JwkException | SignatureVerificationException e) {
            return false;
        }
        return true;
    }
}
