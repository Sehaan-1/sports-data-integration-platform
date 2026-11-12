package com.sdip.api;

import com.sdip.connectors.SyncOrchestrator;
import com.sdip.connectors.SyncReport;
import com.sdip.domain.entity.ConnectorState;
import com.sdip.domain.repo.ConnectorStateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Manual connector control (§4.1). ADMIN only. */
@RestController
@RequestMapping("/api/connectors")
@PreAuthorize("hasRole('ADMIN')")
public class ConnectorController {

    private final SyncOrchestrator orchestrator;
    private final ConnectorStateRepository states;

    public ConnectorController(SyncOrchestrator orchestrator, ConnectorStateRepository states) {
        this.orchestrator = orchestrator;
        this.states = states;
    }

    @PostMapping("/{vendor}/sync")
    public SyncReport sync(@PathVariable String vendor) {
        return orchestrator.sync(vendor);
    }

    @GetMapping("/state")
    public List<ConnectorState> state() {
        return states.findAll();
    }
}
