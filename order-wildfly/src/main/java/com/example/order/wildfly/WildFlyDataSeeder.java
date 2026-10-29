package com.example.order.wildfly;

import com.example.order.core.service.SeedService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

@Singleton
@Startup
public class WildFlyDataSeeder {

    @Inject
    private SeedService seedService;

    @PostConstruct
    public void init() {
        seedService.seed();
    }
}
