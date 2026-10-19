package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.dto.RecipientRequest;
import com.vatsasarthak.notifyr.dto.RecipientResponse;
import com.vatsasarthak.notifyr.entity.Recipient;
import com.vatsasarthak.notifyr.entity.RecipientStatus;
import com.vatsasarthak.notifyr.exception.DuplicateEmailException;
import com.vatsasarthak.notifyr.exception.RecipientNotFoundException;
import com.vatsasarthak.notifyr.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class RecipientService {

    private final RecipientRepository recipientRepository;

    @Transactional
    public RecipientResponse create(RecipientRequest request){

        recipientRepository.findByEmail(request.getEmail()).ifPresent(recipient -> {
            throw new DuplicateEmailException(request.getEmail());
        });

        Recipient recipient = Recipient.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .deviceToken(request.getDeviceToken())
                .preferredLanguage(request.getPreferredLanguage())
                .timeZone(request.getTimeZone())
                .tags(request.getTags() != null ? new ArrayList<>(request.getTags()) : new ArrayList<>())
                .build();

        recipient = recipientRepository.save(recipient);
        return mapToResponse(recipient);
    }

    @Transactional(readOnly = true)
    public RecipientResponse getById(Long id){
        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));
        return mapToResponse(recipient);
    }

    @Transactional(readOnly = true)
    public PageResponse<RecipientResponse> getAll(Pageable pageable){
        return PageResponse.of(recipientRepository.findAll(pageable), this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<RecipientResponse> search(String keyword, Pageable pageable){
        return PageResponse.of(recipientRepository.searchByNameOrEmail(keyword, pageable), this::mapToResponse);
    }

    @Transactional
    public RecipientResponse update(Long id, RecipientRequest request){

        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));

        // the new email should not be already taken
        if(!recipient.getEmail().equals(request.getEmail())) {
            recipientRepository.findByEmail(request.getEmail()).ifPresent(existingRecipient -> {
                throw new DuplicateEmailException(request.getEmail());
            });
        }

        recipient.setName(request.getName());
        recipient.setEmail(request.getEmail());
        recipient.setPhoneNumber(request.getPhoneNumber());
        recipient.setDeviceToken(request.getDeviceToken());
        recipient.setPreferredLanguage(request.getPreferredLanguage());
        recipient.setTimeZone(request.getTimeZone());
        recipient.setTags(request.getTags() != null ? new ArrayList<>(request.getTags()) : new ArrayList<>());

        recipient = recipientRepository.save(recipient);
        return mapToResponse(recipient);
    }

    @Transactional
    public void delete(Long id){
        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));

        recipientRepository.delete(recipient);
    }

    // update status
    @Transactional
    public RecipientResponse updateStatus(Long id, RecipientStatus status){
        Recipient recipient = recipientRepository.findById(id)
                .orElseThrow(() -> new RecipientNotFoundException(id));

        recipient.setStatus(status);
        recipient = recipientRepository.save(recipient);
        return mapToResponse(recipient);
    }

    private RecipientResponse mapToResponse(Recipient recipient) {

        return new RecipientResponse(
                recipient.getId(),
                recipient.getName(),
                recipient.getEmail(),
                recipient.getPhoneNumber(),
                recipient.getDeviceToken(),
                recipient.getPreferredLanguage(),
                recipient.getTimeZone(),
                recipient.getTags() != null ? new ArrayList<>(recipient.getTags()) : new ArrayList<>(),
                recipient.getStatus(),
                recipient.getCreatedAt(),
                recipient.getUpdatedAt()
        );
    }
}
