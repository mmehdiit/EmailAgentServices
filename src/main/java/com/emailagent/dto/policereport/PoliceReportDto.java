package com.emailagent.dto.policereport;

import java.io.Serializable;

import lombok.Data;

@Data
public class PoliceReportDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public ReportInfoDto reportInfo = new ReportInfoDto();

    public PartiesDto parties = new PartiesDto();

    public MetaDto meta = new MetaDto();

}
