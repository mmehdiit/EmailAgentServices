package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import lombok.Data;

@Data
public class PartyDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String role;

    // Identity
    public String driverName;

    public String ownerName;

    public String nationality;

    public String gender;

    public LocalDate dateOfBirth;

    // Contact
    public String mobileNumber;

    // Vehicle
    public VehicleDto vehicle = new VehicleDto();

    // Insurance
    public InsuranceDto insurance = new InsuranceDto();

    // License
    public LicenseDto license = new LicenseDto();

    // Damage
    public DamageDto damage = new DamageDto();

    // Optional: keep original raw fields if you want (debugging)
    public Map<String, String> raw = new HashMap<>();
}
