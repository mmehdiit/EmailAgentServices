package com.emailagent.dto.policereport;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetaDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("pagesProcessed")
    public int pagesProcessed;

    @JsonProperty("documentType")
    public String documentType;

    @JsonProperty("securityCode")
    public String securityCode;

    @JsonProperty("stampAndSignature")
    public String stampAndSignature;
}
