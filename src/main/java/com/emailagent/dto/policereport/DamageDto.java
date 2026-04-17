package com.emailagent.dto.policereport;

import java.io.Serializable;

import lombok.Data;

@Data
public class DamageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public String level;

    public String damagedParts;
}
