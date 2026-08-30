package com.knowimran.rivoquery.generation;

import java.util.Objects;

public interface SqlGenerationModel {

    GenerationResponse generate(GenerationRequest request) throws GenerationException;

    class GenerationException extends Exception {
        public GenerationException(String message) {
            super(message);
        }

        public GenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
