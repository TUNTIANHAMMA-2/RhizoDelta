package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.repository.TopicRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TopicService {
    private final TopicRepository topicRepository;

    public TopicService(TopicRepository topicRepository) {
        this.topicRepository = topicRepository;
    }

    /**
     * 返回名为 {@code name} 的 Topic 的 {@code topic_id}。Topic 不存在则创建。
     *
     * <p>{@code name} 为 null 或仅包含空白字符时，返回 null —— 调用方负责处理这种"无主题语义"的场景，
     * 而不是默默创建一个 {@code name=null} 的孤儿节点。
     */
    public String getOrCreateTopic(String name, String sourceType) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        String topicId = UUID.randomUUID().toString();
        String resolvedSourceType = sourceType == null || sourceType.isBlank() ? "UNKNOWN" : sourceType;
        return topicRepository.upsert(topicId, trimmed, resolvedSourceType);
    }
}
