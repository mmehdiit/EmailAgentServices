package com.emailagent.dto.notification;

import java.io.Serializable;
import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class NewNotificationByReportResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("notificationId")
    private String notificationId;

    @JsonProperty("notificationVisa")
    private BigDecimal notificationVisa;

    @JsonProperty("carId")
    private String carId;

    @JsonProperty("reportNumber")
    private String reportNumber;
}
