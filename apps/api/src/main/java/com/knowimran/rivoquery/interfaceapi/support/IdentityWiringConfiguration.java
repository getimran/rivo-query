package com.knowimran.rivoquery.interfaceapi.support;

import com.knowimran.rivoquery.identity.WorkspaceActorResolver;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link WorkspaceActorResolver} lives in the framework-free {@code identity}
 * core package (see {@code ArchitectureTest.core_modules_do_not_depend_on_adapters_or_frameworks}),
 * so it cannot carry a Spring stereotype annotation itself; this wires it as
 * a bean from the transport layer instead.
 */
@Configuration
public class IdentityWiringConfiguration {

    @Bean
    WorkspaceActorResolver workspaceActorResolver(CurrentMembershipRepository memberships) {
        return new WorkspaceActorResolver(memberships);
    }
}
