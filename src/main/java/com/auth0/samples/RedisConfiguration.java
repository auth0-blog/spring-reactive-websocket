package com.auth0.samples;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${redis.url}")
    private String redisUrl;

    @Value("${redis.channel}")
    private String redisChannel;

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create(redisUrl);
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
        reactiveConnection.subscribe(redisChannel).subscribe();
        Publisher<Message<?>> p = reactiveConnection.observeChannels()
                .map(channelMessage -> new GenericMessage<>(channelMessage.getMessage()));
        return IntegrationFlows.from(p).channel(channel).get();
    }
}
