package com.example.order.liberty;

import com.example.order.core.service.SeedService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

/**
 * Liberty-specific startup hook. The portable core only exposes SeedService.
 */
@Singleton
@Startup
public class LibertyDataSeeder {

    @Inject
    private SeedService seedService;

    @PostConstruct
    public void init() {
        seedService.seed();
    }
}
