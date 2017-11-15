package com.auth0.samples;

import com.auth0.json.auth.UserInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
class MessageEvent {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Date when;
    private String sub;
    private String name;
    private String picture;
    private String message;

    MessageEvent(UserInfo userInfo, String message) {
        this.when = new Date();
        this.name = (String) userInfo.getValues().get("name");
        this.picture = (String) userInfo.getValues().get("picture");
        this.sub = (String) userInfo.getValues().get("sub");
        this.message = message;
    }

    @Override
    public String toString() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    static MessageEvent fromJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, MessageEvent.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
