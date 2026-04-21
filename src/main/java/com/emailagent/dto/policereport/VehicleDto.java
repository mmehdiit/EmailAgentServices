package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String make;

    public String model;

    public String color;

    @JsonProperty("plateNumber")
    public Long plateNumber;

    public String year;

    @JsonProperty("registrationExpiry")
    public LocalDate registrationExpiry;

    @JsonProperty("plateRaw")
    public String plateRaw;
}
