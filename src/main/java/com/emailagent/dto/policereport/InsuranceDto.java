package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InsuranceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String company;

    @JsonProperty("policyNumber")
    public String policyNumber;

    @JsonProperty("expiryDate")
    public LocalDate expiryDate;
}
