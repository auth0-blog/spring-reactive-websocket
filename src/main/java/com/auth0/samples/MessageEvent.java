package com.auth0.samples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

@Data
@NoArgsConstructor
@AllArgsConstructor
class MessageEvent {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String when;
    private String sub;
    private String name;
    private String picture;
    private String message;

    @Override
    public String toString() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static MessageEvent fromJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, MessageEvent.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
