package com.rhizodelta.api;

import com.rhizodelta.config.AuthenticatedUser;
import com.rhizodelta.domain.association.AssociationResult;
import com.rhizodelta.service.AssociationService;
import com.rhizodelta.domain.association.CreateAssociationCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/associations")
public class AssociationController {
    private final AssociationService associationService;

    public AssociationController(AssociationService associationService) {
        this.associationService = associationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AssociationResult>> create(
            @Valid @RequestBody CreateAssociationCommand command,
            Authentication authentication
    ) {
        AuthenticatedUser authenticatedUser = requireAuthenticatedUser(authentication);
        CreateAssociationCommand authenticatedCommand = new CreateAssociationCommand(
                command.source_node_id(),
                command.target_node_id(),
                command.type(),
                authenticatedUser.sub(),
                command.reason(),
                command.confidence()
        );
        AssociationService.CreateAssociationOutcome outcome = associationService.createAssociation(authenticatedCommand);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(outcome.association()));
    }

    @DeleteMapping("/{associationId}")
    public ResponseEntity<ApiResponse<DeleteAssociationResponse>> delete(@PathVariable("associationId") UUID associationId) {
        AssociationService.DeleteAssociationOutcome outcome = associationService.deleteAssociation(associationId);
        DeleteAssociationResponse response = new DeleteAssociationResponse(outcome.association_id(), outcome.deleted());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    public record DeleteAssociationResponse(UUID association_id, boolean deleted) {
    }

    private static AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }
}
