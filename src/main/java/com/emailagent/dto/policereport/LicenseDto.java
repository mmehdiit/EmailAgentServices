package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LicenseDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("licenseNumber")
    public String licenseNumber;

    @JsonProperty("expiryDate")
    public LocalDate expiryDate;

    @JsonProperty("issuePlace")
    public String issuePlace;
}
