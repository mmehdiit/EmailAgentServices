package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.time.LocalDate;

import lombok.Data;

@Data
public class InsuranceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String company;

    public String policyNumber;

    public LocalDate expiryDate;
}
