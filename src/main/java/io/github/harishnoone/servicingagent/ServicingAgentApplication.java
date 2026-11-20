package io.github.harishnoone.servicingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servicing Agent POC: autonomous Tier-1 customer servicing agent
 * <p>
 * Virtual Threads (JDK 21 / Project Loom) are enabled via:
 * spring.threads.virtual.enabled=true  in application.yml
 * <p>
 * This allows Spring MVC and @KafkaListener threads to use virtual threads,
 * eliminating platform thread starvation under concurrent tool I/O load
 * without requiring reactive/WebFlux programming (NFR-3).
 */
@SpringBootApplication
public class ServicingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicingAgentApplication.class, args);
    }
}
