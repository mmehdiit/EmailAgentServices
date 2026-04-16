package com.emailagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EmailAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmailAgentApplication.class, args);
    }
}
