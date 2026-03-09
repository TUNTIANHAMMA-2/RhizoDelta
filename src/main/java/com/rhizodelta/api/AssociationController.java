package com.rhizodelta.api;

import com.rhizodelta.service.AssociationResult;
import com.rhizodelta.service.AssociationService;
import com.rhizodelta.service.CreateAssociationCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/associations")
public class AssociationController {
    private final AssociationService associationService;

    public AssociationController(AssociationService associationService) {
        this.associationService = associationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AssociationResult>> create(@RequestBody CreateAssociationCommand command) {
        AssociationService.CreateAssociationOutcome outcome = associationService.createAssociation(command);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(outcome.association()));
    }
}
