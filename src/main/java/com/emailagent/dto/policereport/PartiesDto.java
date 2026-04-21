package com.emailagent.dto.policereport;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartiesDto implements Serializable {

    private static final long serialVersionUID = 1L;

    public List<PartyDto> liable = new ArrayList<>();

    public List<PartyDto> affected = new ArrayList<>();
}
