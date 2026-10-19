package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.PageResponse;
import com.vatsasarthak.notifyr.dto.TemplateRequest;
import com.vatsasarthak.notifyr.dto.TemplateResponse;
import com.vatsasarthak.notifyr.entity.Template;
import com.vatsasarthak.notifyr.exception.DuplicateTemplateNameException;
import com.vatsasarthak.notifyr.exception.TemplateNotFoundException;
import com.vatsasarthak.notifyr.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository templateRepository;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    @Transactional
    public TemplateResponse create(TemplateRequest request){

        templateRepository.findByName(request.getName()).ifPresent(template -> {
            throw new DuplicateTemplateNameException(request.getName());
        });

        Template template = Template.builder()
                .name(request.getName())
                .subject(request.getSubject())
                .body(request.getBody())
                .build();

        template = templateRepository.save(template);
        return mapToResponse(template);
    }

    @Transactional(readOnly = true)
    public TemplateResponse getById(Long id){
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException(id));
        return mapToResponse(template);
    }

    @Transactional(readOnly = true)
    public PageResponse<TemplateResponse> getAll(Pageable pageable){
        return PageResponse.of(templateRepository.findAll(pageable), this::mapToResponse);
    }

    @Transactional
    public TemplateResponse update(Long id, TemplateRequest request){

        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException(id));

        // new template name mustn't be already taken
        if(!template.getName().equals(request.getName())) {
            templateRepository.findByName(request.getName()).ifPresent(existingTemplate -> {
                throw new DuplicateTemplateNameException(request.getName());
            });
        }

        template.setName(request.getName());
        template.setSubject(request.getSubject());
        template.setBody(request.getBody());

        template = templateRepository.save(template);
        return mapToResponse(template);
    }

    @Transactional
    public void delete(Long id){
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new TemplateNotFoundException(id));
        templateRepository.delete(template);
    }

    public String render(String text, Map<String, String> variables){

        if(text == null)  return null;

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while(matcher.find()){
            String key = matcher.group(1).trim();
            String value = variables != null ? variables.get(key) : null;
            String replacement = value != null ? value : matcher.group(0); // keep as is if val is null
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
    private TemplateResponse mapToResponse(Template template) {
        return new TemplateResponse(
                template.getId(),
                template.getName(),
                template.getSubject(),
                template.getBody(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
