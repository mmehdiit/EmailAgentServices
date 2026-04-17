package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

import lombok.Data;

@Data
public class ReportInfoDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String reportNumber;
    public LocalDate reportDate;
    public LocalTime reportTime;

    public LocalDate accidentDate;
    public LocalTime accidentTime;

    public String reportType;
    public String policeStation;

    public String primaryCause;
    public String secondaryCause;

    public String roadCondition;
    public String visibility;
    public String accidentType;

    public String emirateCityArea;
    public Integer numberOfVehicles;
}
