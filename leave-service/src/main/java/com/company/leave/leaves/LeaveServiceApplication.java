package com.company.leave.leaves;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.company.leave.leaves", "com.company.leave.common"})
@EnableScheduling
@ConfigurationPropertiesScan
public class LeaveServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeaveServiceApplication.class, args);
    }
}
