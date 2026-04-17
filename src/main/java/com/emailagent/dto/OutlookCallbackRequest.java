package com.emailagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class OutlookCallbackRequest {
    private String code;
    @JsonProperty("frontendOrigin")
    private String frontendOrigin;
    @JsonProperty("redirectUri")
    private String redirectUri;
}
