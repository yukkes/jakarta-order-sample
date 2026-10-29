package com.example.order.core;

import com.example.order.core.service.SeedService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;

/**
 * CDI 4.1 startup event. Quarkus's StartupEvent extends
 * jakarta.enterprise.event.Startup, so the same observer runs on Quarkus,
 * Liberty, WildFly and GlassFish.
 */
@ApplicationScoped
public class StartupDataSeeder {

    @Inject
    private SeedService seedService;

    void onStart(@Observes Startup event) {
        seedService.seed();
    }
}
