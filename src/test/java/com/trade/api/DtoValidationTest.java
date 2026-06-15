package com.trade.api;

import com.trade.dto.ChatRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldRejectBlankQuestion() {
        ChatRequest request = new ChatRequest(null, " ", null, null, null, null, null, null);

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
