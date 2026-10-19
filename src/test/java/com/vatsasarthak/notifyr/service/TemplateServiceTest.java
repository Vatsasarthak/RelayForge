package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.TemplateRequest;
import com.vatsasarthak.notifyr.entity.Template;
import com.vatsasarthak.notifyr.exception.DuplicateTemplateNameException;
import com.vatsasarthak.notifyr.exception.TemplateNotFoundException;
import com.vatsasarthak.notifyr.repository.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TemplateServiceTest {

    @Mock
    private TemplateRepository templateRepository;

    @InjectMocks
    private TemplateService templateService;

    private Map<String, String> variables;

    @BeforeEach
    void setup(){
        variables = new HashMap<>();
        variables.put("name", "John");
        variables.put("discount", "20");
    }

    @Test
    void render_replacesAllPlaceholdersWhenVariablesProvided(){
        String template = "Hello {{name}}, enjoy {{discount}}% off!";
        String result = templateService.render(template, variables);

        assertThat(result).isEqualTo("Hello John, enjoy 20% off!");
    }

    @Test
    void render_leavesPlaceholderLiteralWhenVariableMissing(){
        String template = "Hello {{name}}, your code is {{promoCode}}";
        String result = templateService.render(template, variables);

        assertThat(result).isEqualTo("Hello John, your code is {{promoCode}}");
    }

    @Test
    void render_handlesMultipleOccurrencesOfSamePlaceholder(){
        String template = "Hello {{name}}, {{name}} is your name. {{name}} you're so cool!";
        String result = templateService.render(template, variables);

        assertThat(result).isEqualTo("Hello John, John is your name. John you're so cool!");
    }

    @Test
    void render_returnsTextUnchangedWhenNoPlaceholdersPresent(){
        String template = "This has no placeholders at all.";
        String result = templateService.render(template, variables);

        assertThat(result).isEqualTo(template);
    }

    @Test
    void render_returnsNullWhenInputTextIsNull(){
        String result = templateService.render(null, variables);
        assertThat(result).isNull();
    }

    @Test
    void render_leavesAllPlaceholdersLiteralWhenVariablesMapIsNull(){
        String template = "Hello {{name}}!";
        String result = templateService.render(template, null);

        assertThat(result).isEqualTo("Hello {{name}}!");
    }

    @Test
    void render_handlesPlaceholderWithSpacesInsideBraces(){
        String template = "Hello {{ name }}!";
        String result = templateService.render(template, variables);

        assertThat(result).isEqualTo("Hello John!");
    }

    @Test
    void create_withExistingName_isAConflictNotANotFound(){
        when(templateRepository.findByName("welcome")).thenReturn(Optional.of(new Template()));

        assertThatThrownBy(() -> templateService.create(new TemplateRequest("welcome", "s", "b")))
                .isInstanceOf(DuplicateTemplateNameException.class)
                .hasMessage("Template with name 'welcome' already exists");
    }

    @Test
    void getById_unknownId_hasReadableMessage(){
        when(templateRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.getById(7L))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessage("Template not found with id: 7");
    }

}
