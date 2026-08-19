package in.sandesh;

import in.sandesh.common.BusinessException;
import in.sandesh.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An endpoint that produces text/event-stream and fails before its first byte must still be able
 * to say why.
 *
 * <p>This is the second half of the outbox-cursor incident. The query failed, and then the error
 * handler failed too: the endpoint's {@code produces} declaration is carried into the error path
 * as the only representation Spring may write, so the JSON body could not be rendered and the
 * client received a 500 with nothing in it. A stream that dies silently is the hardest kind of
 * failure to diagnose from a phone on a construction site.</p>
 */
class ErrorBodyOnStreamEndpointTest {

    /** Stands in for StreamController: the same declaration, failing the same way. */
    @RestController
    static class StreamingStub {

        @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter open() {
            throw new IllegalStateException("the database refused the statement");
        }

        @GetMapping(path = "/stream/refused", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter refused() {
            throw new BusinessException("blocked", "You cannot connect.", HttpStatus.FORBIDDEN);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StreamingStub())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void anUnexpectedFailureCarriesAJsonBodyDespiteTheStreamDeclaration() throws Exception {
        mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("internal"))
                .andExpect(jsonPath("$.detail").value("Something went wrong. Try again."));
    }

    @Test
    void aRefusedConnectionSaysWhichRefusalItWas() throws Exception {
        mvc.perform(get("/stream/refused").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("blocked"));
    }
}
