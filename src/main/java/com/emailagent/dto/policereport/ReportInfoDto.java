package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportInfoDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("reportNumber")
    public String reportNumber;

    @JsonProperty("reportDate")
    public LocalDate reportDate;

    @JsonProperty("reportTime")
    public LocalTime reportTime;

    @JsonProperty("accidentDate")
    public LocalDate accidentDate;

    @JsonProperty("accidentTime")
    public LocalTime accidentTime;

    @JsonProperty("reportType")
    public String reportType;

    @JsonProperty("policeStation")
    public String policeStation;

    @JsonProperty("primaryCause")
    public String primaryCause;

    @JsonProperty("secondaryCause")
    public String secondaryCause;

    @JsonProperty("roadCondition")
    public String roadCondition;

    public String visibility;

    @JsonProperty("accidentType")
    public String accidentType;

    @JsonProperty("emirateCityArea")
    public String emirateCityArea;

    @JsonProperty("numberOfVehicles")
    public Integer numberOfVehicles;
}
