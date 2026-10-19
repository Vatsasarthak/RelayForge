package com.vatsasarthak.notifyr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vatsasarthak.notifyr.config.CorrelationIdFilter;
import com.vatsasarthak.notifyr.entity.OutboxEvent;
import com.vatsasarthak.notifyr.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

// Records a message for publication. MANDATORY propagation: it must join the caller's transaction,
// otherwise the message and the state change it belongs to could be committed separately.
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(String aggregateType, Long aggregateId, String exchange, String routingKey,
                               Object payload) {
        return enqueue(aggregateType, aggregateId, exchange, routingKey, payload, Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(String aggregateType, Long aggregateId, String exchange, String routingKey,
                               Object payload, Map<String, String> headers) {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .exchange(exchange)
                .routingKey(routingKey)
                .payload(toJson(payload))
                .messageHeaders(headers.isEmpty() ? null : toJson(headers))
                .correlationId(MDC.get(CorrelationIdFilter.MDC_KEY))
                .build();
        return outboxEventRepository.save(event);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize outbox payload", e);
        }
    }
}
