package it.iren.loyalty.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Eventi in uscita verso CRM, marketing automation e data platform (RI-06) */
@SpringBootApplication
public class NotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }
}
