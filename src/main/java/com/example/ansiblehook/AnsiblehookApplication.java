package com.example.ansiblehook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AnsiblehookProperties.class)
public class AnsiblehookApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnsiblehookApplication.class, args);
	}

}
