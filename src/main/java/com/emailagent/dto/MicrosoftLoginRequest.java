package com.emailagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MicrosoftLoginRequest {

    @NotBlank
    @JsonProperty("idToken")
    private String idToken;
}
