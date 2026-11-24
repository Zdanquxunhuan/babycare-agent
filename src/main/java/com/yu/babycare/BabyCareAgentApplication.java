package com.yu.babycare;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BabyCareAgentApplication {

	@Bean
	Clock storeClock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }

	public static void main(String[] args) {
		SpringApplication.run(BabyCareAgentApplication.class, args);
	}

}
