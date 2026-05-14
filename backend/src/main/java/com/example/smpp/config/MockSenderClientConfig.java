package com.example.smpp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MockSenderClientConfig {

    @Bean(name = "mockSenderRestClient")
    public RestClient mockSenderRestClient(MockSenderProperties properties) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(properties.getConnectTimeoutMs());
        rf.setReadTimeout(properties.getReadTimeoutMs());
        return RestClient.builder().requestFactory(rf).build();
    }
}
