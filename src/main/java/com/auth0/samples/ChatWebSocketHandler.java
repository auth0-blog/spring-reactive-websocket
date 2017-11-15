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
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;
import java.util.function.Consumer;

@Component
public class ChatWebSocketHandler implements WebSocketHandler {
    @Value("${auth0.domain}")
    private String auth0Domain;

    @Value("${auth0.clientId}")
    private String auth0ClientId;

    @Value("${auth0.clientSecret}")
    private String auth0ClentSecret;

    @Value("${redis.channel}")
    private String redisChannel;

    private RedisClient redisClient;

    private PublishSubscribeChannel channel;

    public ChatWebSocketHandler(RedisClient redisClient, PublishSubscribeChannel channel) {
        this.redisClient = redisClient;
        this.channel = channel;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
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
        ForwardingMessageHandler messageHandler = new ForwardingMessageHandler(session);
        return Flux.create((Consumer<FluxSink<WebSocketMessage>>) fluxSink -> {
            messageHandler.setSink(fluxSink);
            channel.subscribe(messageHandler);
        }).doFinally(signalType -> channel.unsubscribe(messageHandler));
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
