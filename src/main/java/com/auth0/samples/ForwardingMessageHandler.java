package com.auth0.samples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.FluxSink;

import java.io.File;

public class ForwardingMessageHandler implements MessageHandler {
    private WebSocketSession session;
    private FluxSink<WebSocketMessage> sink;
    private String sessionId;
    private ObjectMapper objectMapper;

    public ForwardingMessageHandler(WebSocketSession session, FluxSink<WebSocketMessage> sink) {
        this.session = session;
        this.sink = sink;
        this.sessionId = session.getId();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void handleMessage(Message<?> message) throws MessagingException {
        try {
            File payload = (File) message.getPayload();
            FileEvent event = new FileEvent(sessionId, payload.getPath());
            WebSocketMessage textMessage = session.textMessage(objectMapper.writeValueAsString(event));
            sink.next(textMessage);
        } catch (JsonProcessingException e) {
            throw new MessagingException(e.getMessage());
        }
    }
}
