package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.PreferenceEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PreferenceEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreferenceEventService.class);

    private final PreferenceEventRepository repository;

    public PreferenceEventService(PreferenceEventRepository repository) {
        this.repository = repository;
    }

    public void recordEvent(String userId, String topicId, String type, double weight, String sourceNodeId) {
        try {
            repository.createEvent(userId, topicId, UUID.randomUUID().toString(), type, weight, sourceNodeId);
        } catch (Exception e) {
            LOGGER.debug("Failed to record preference event type={} for user={}: {}", type, userId, e.getMessage());
        }
    }
}
