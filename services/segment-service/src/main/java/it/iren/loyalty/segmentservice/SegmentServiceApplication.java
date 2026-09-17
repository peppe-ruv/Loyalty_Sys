package it.iren.loyalty.segmentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Segmenti dinamici e statici, ricalcolo pianificato, export, targeting per regole/catalogo/contenuti (RF-65, RF-71) */
@SpringBootApplication
@EnableScheduling
public class SegmentServiceApplication {
    public static void main(String[] args) { SpringApplication.run(SegmentServiceApplication.class, args); }
}
