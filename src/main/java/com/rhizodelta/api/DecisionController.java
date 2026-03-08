package com.rhizodelta.api;

import com.rhizodelta.service.BranchDecisionCommand;
import com.rhizodelta.service.DecisionResult;
import com.rhizodelta.service.DecisionService;
import com.rhizodelta.service.MergeDecisionCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<DecisionResult>> merge(@RequestBody MergeDecisionCommand command) {
        DecisionResult result = decisionService.executeMerge(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/branch")
    public ResponseEntity<ApiResponse<DecisionResult>> branch(@RequestBody BranchDecisionCommand command) {
        DecisionResult result = decisionService.executeBranch(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }
}
