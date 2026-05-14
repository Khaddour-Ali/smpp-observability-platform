package com.example.smpp;

import com.example.smpp.config.MockSenderProperties;
import com.example.smpp.config.QueueProperties;
import com.example.smpp.config.SmppServerProperties;
import com.example.smpp.config.SmppThrottleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    SmppServerProperties.class,
    SmppThrottleProperties.class,
    QueueProperties.class,
    MockSenderProperties.class
})
public class SmppObservabilityPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmppObservabilityPlatformApplication.class, args);
    }
}
