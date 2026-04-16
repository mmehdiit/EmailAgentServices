package com.emailagent.dto;

import lombok.Data;

@Data
public class OutlookCallbackRequest {
    private String code;
    private String frontendOrigin;
    private String redirectUri;
}
