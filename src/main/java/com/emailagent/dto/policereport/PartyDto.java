package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartyDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String role;

    @JsonProperty("driverName")
    public String driverName;

    @JsonProperty("ownerName")
    public String ownerName;

    public String nationality;

    public String gender;

    @JsonProperty("dateOfBirth")
    public LocalDate dateOfBirth;

    @JsonProperty("mobileNumber")
    public String mobileNumber;

    public VehicleDto vehicle = new VehicleDto();

    public InsuranceDto insurance = new InsuranceDto();

    public LicenseDto license = new LicenseDto();

    public DamageDto damage = new DamageDto();

    public Map<String, String> raw = new HashMap<>();
}
