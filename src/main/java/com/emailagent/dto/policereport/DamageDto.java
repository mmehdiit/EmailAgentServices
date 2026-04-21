package com.emailagent.dto.policereport;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DamageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String level;

    @JsonProperty("damagedParts")
    public String damagedParts;
}
