package com.example.order.quarkus;

import com.example.order.core.service.SeedService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Quarkus-specific startup hook. Seeding is kept out of the portable core
 * because Quarkus initializes Hibernate ORM after the CDI ApplicationScoped
 * context is started.
 */
@ApplicationScoped
public class QuarkusDataSeeder {

    @Inject
    private SeedService seedService;

    void onStart(@Observes StartupEvent event) {
        seedService.seed();
    }
}
