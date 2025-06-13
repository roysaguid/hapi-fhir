package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.rest.server.RestfulServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

import ca.uhn.fhir.jpa.starter.CustomForwardingInterceptor;

@Configuration
public class CustomInterceptorConfig {

    @Autowired
    private RestfulServer restfulServer;

    @PostConstruct
    public void registerInterceptors() {
        restfulServer.registerInterceptor(new CustomForwardingInterceptor());
    }
}