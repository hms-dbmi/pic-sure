package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins what {@link GlobalExceptionHandler} writes for a {@link PicSureResponseException}: the status the exception names and a
 * {@code {message, content}} body built from it. The exception extends {@link RuntimeException}, so the last two cases prove its own
 * mapping wins over the 500 fallback, and that an {@link IllegalArgumentException} still answers 400 in the same body shape.
 */
class PicSureResponseExceptionTest {

    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        @GetMapping("/missing")
        public String missing() {
            throw new PicSureResponseException(HttpStatus.NOT_FOUND, "AccessRule not found", null);
        }

        @GetMapping("/rejected")
        public String rejected() {
            throw new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "Privilege not found");
        }

        @GetMapping("/failed")
        public String failed() {
            throw new PicSureResponseException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Application error", "Inner application error, please contact admin."
            );
        }

        @GetMapping("/illegal")
        public String illegal() {
            throw new IllegalArgumentException("Connection with id nope not found");
        }
    }

    private final MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void notFoundKeepsItsStatusAndWritesANullContent() throws Exception {
        mockMvc.perform(get("/probe/missing")).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"message\":\"AccessRule not found\",\"content\":null}"));
    }

    @Test
    void badRequestCarriesTheDetailAsContent() throws Exception {
        mockMvc.perform(get("/probe/rejected")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"Privilege not found\"}"));
    }

    @Test
    void serverErrorIsNotSwallowedByTheRuntimeFallback() throws Exception {
        mockMvc.perform(get("/probe/failed")).andExpect(status().isInternalServerError()).andExpect(
            content().string("{\"message\":\"Application error\",\"content\":\"Inner application error, please contact admin.\"}")
        );
    }

    @Test
    void illegalArgumentAnswersTheSameBodyShape() throws Exception {
        mockMvc.perform(get("/probe/illegal")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"Connection with id nope not found\"}"));
    }
}
