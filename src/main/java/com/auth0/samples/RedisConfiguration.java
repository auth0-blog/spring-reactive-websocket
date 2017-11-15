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
import org.springframework.messaging.support.GenericMessage;

@Configuration
public class RedisConfiguration {
    private static final String REDIS_URL = "redis://localhost";
    static final String REDIS_MESSAGING_CHANNEL = "messaging-channel";

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create(REDIS_URL);
    }

    @Bean
    @Primary
    public PublishSubscribeChannel pubSubChannel() {
        return new PublishSubscribeChannel();
    }

    @Bean
    public IntegrationFlow messageFlow(PublishSubscribeChannel channel, RedisClient redisClient) {
        StatefulRedisPubSubConnection<String, String> pubSub = redisClient.connectPubSub();
        RedisPubSubReactiveCommands<String, String> reactiveConnection = pubSub.reactive();
        reactiveConnection.subscribe(REDIS_MESSAGING_CHANNEL).subscribe();
        Publisher<Message<?>> p = reactiveConnection.observeChannels()
                .map(channelMessage -> new GenericMessage<>(channelMessage.getMessage()));
        return IntegrationFlows.from(p).channel(channel).get();
    }
}
