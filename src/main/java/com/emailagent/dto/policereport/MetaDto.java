package com.emailagent.dto.policereport;

import java.io.Serializable;

import lombok.Data;

@Data
public class MetaDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public int pagesProcessed;

    public String documentType;

    public String securityCode;

    public String stampAndSignature;
}
