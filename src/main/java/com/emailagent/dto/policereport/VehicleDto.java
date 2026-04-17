package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;

import lombok.Data;

@Data
public class VehicleDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String make;

    public String model;

    public String color;

    public Long plateNumber;

    public String year;

    public LocalDate registrationExpiry;

    public String plateRaw;
}
