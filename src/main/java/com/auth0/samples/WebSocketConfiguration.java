package com.auth0.samples;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.file.dsl.FileInboundChannelAdapterSpec;
import org.springframework.integration.file.dsl.Files;
import org.springframework.messaging.MessageHandler;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


@Configuration
public class WebSocketConfiguration {
    @Bean
    public IntegrationFlow fileFlow(PublishSubscribeChannel channel, @Value("file://${HOME}/Desktop/in") File file) {
        FileInboundChannelAdapterSpec in = Files.inboundAdapter(file).autoCreateDirectory(true);
//        return IntegrationFlows.from(
//        in,
//                new Consumer<SourcePollingChannelAdapterSpec>() {
//                    @Override
//                    public void accept(SourcePollingChannelAdapterSpec p) {
//                        p.poller(new Function<PollerFactory, PollerSpec>() {
//                            @Override
//                            public PollerSpec apply(PollerFactory pollerFactory) {
//                                return pollerFactory.fixedRate(1000);
//                            }
//                        });
//                    }
//                }
        return IntegrationFlows.from(
                in,
                p -> p.poller(pollerFactory -> {
                    return pollerFactory.fixedRate(1000);
                })
        ).channel(channel).get();
    }

    @Bean
    @Primary
    public PublishSubscribeChannel incomingFilesChannel() {
        return new PublishSubscribeChannel();
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public WebSocketHandler webSocketHandler(PublishSubscribeChannel channel) {
        return session -> {
            Map<String, MessageHandler> connections = new ConcurrentHashMap<>();
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
        handlerMapping.setUrlMap(Collections.singletonMap("/ws/files", webSocketHandler));
        return handlerMapping;
    }
}
