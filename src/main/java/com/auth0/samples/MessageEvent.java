package com.auth0.samples;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
class MessageEvent {
    private String when;
    private String message;
}
