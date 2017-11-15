package com.auth0.samples;

import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.UserInfo;
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
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Configuration
public class WebSocketConfiguration {
    private final Map<String, MessageHandler> connections = new ConcurrentHashMap<>();

    @Value("${auth0.domain}")
    private String auth0Domain;

    @Value("${auth0.clientId}")
    private String auth0ClientId;

    @Value("${auth0.clientSecret}")
    private String auth0ClentSecret;

    @Value("${redis.channel}")
    private String redisChannel;

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
            String verifiedToken = authenticateHandshake(session.getHandshakeInfo());
            if (verifiedToken == null) {
                return session.receive().then(Mono.create(monoSink -> {
                    session.close(CloseStatus.POLICY_VIOLATION);
                }));
            }

            try {
                AuthAPI auth = new AuthAPI(auth0Domain, auth0ClientId, auth0ClentSecret);
                UserInfo userInfo = auth.userInfo(verifiedToken).execute();

                listenToMessages(session, userInfo, redisClient);
                return session.send(publishMessages(session, channel));
            } catch (Auth0Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private void listenToMessages(WebSocketSession session, UserInfo userInfo, RedisClient redisClient) {
        session.receive().flatMap(webSocketMessage ->
                Mono.just(new MessageEvent(userInfo, webSocketMessage.getPayloadAsText()))
        ).subscribe(messageEvent -> redisClient.connectPubSub()
                .reactive()
                .publish(redisChannel, messageEvent.toString())
                .subscribe());
    }

    private Flux<WebSocketMessage> publishMessages(WebSocketSession session, PublishSubscribeChannel channel) {
        return Flux.create((Consumer<FluxSink<WebSocketMessage>>) fluxSink -> {
            connections.put(session.getId(), new ForwardingMessageHandler(session, fluxSink));
            channel.subscribe(connections.get(session.getId()));
        }).doFinally(signalType -> {
            channel.unsubscribe(connections.get(session.getId()));
            connections.remove(session.getId());
        });
    }

    private String authenticateHandshake(HandshakeInfo handshake) {
        String queryParams = handshake.getUri().getQuery();
        if (queryParams == null) {
            return null;
        }
        String token = queryParams.replace("token=", "");
        if (token.isEmpty()) {
            return null;
        }

        DecodedJWT jwt = JWT.decode(token);
        String kid = jwt.getKeyId();
        JwkProvider provider = new UrlJwkProvider(auth0Domain);

        try {
            Jwk jwk = provider.get(kid);
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);
            JWTVerifier verifier = JWT.require(algorithm).build();
            verifier.verify(token);
        } catch (JwkException | SignatureVerificationException e) {
            return null;
        }
        return token;
    }
}
