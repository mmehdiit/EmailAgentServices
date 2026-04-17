package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;

import lombok.Data;

@Data
public class LicenseDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String licenseNumber;

    public LocalDate expiryDate;

    public String issuePlace;

}
