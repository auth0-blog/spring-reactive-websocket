package com.auth0.samples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.FluxSink;

import java.util.Date;

public class ForwardingMessageHandler implements MessageHandler {
    private WebSocketSession session;
    private FluxSink<WebSocketMessage> sink;
    private ObjectMapper objectMapper;

    ForwardingMessageHandler(WebSocketSession session, FluxSink<WebSocketMessage> sink) {
        this.session = session;
        this.sink = sink;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void handleMessage(Message<?> message) throws MessagingException {
        try {
            MessageEvent messageEvent = MessageEvent.fromJson((String) message.getPayload());
            String textMessageEvent = objectMapper.writeValueAsString(messageEvent);
            System.out.println(textMessageEvent);
            WebSocketMessage textMessage = session.textMessage(textMessageEvent);
            sink.next(textMessage);
        } catch (JsonProcessingException e) {
            throw new MessagingException(e.getMessage());
        }
    }
}
