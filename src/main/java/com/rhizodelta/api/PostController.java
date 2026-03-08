package com.rhizodelta.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.service.PostService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private static final String QUEUED_STATUS = "QUEUED";

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PostAcceptedResponse>> createPost(@RequestBody CreatePostRequest request) {
        validateRequest(request);

        PostService.CreateHumanPostCommand command = new PostService.CreateHumanPostCommand(
                request.requestId(),
                request.authorId(),
                request.content()
        );

        HumanPost createdPost = postService.createHumanPost(command);
        PostAcceptedResponse response = new PostAcceptedResponse(createdPost.getNodeId().toString(), QUEUED_STATUS);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    private void validateRequest(CreatePostRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        requireText(request.requestId(), "request_id");
        requireText(request.authorId(), "author_id");
        requireText(request.content(), "content");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public record CreatePostRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("content") String content,
            @JsonProperty("target_node_id") String targetNodeId
    ) {
    }

    public record PostAcceptedResponse(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("status") String status
    ) {
    }
}
