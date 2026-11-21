package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** A small client over MockMvc for the API tests: send a request, get the status and body back. */
public final class Http {

    static final ObjectMapper JSON = ApiJson.mapper();

    private final MockMvc mvc;
    private final String header;
    private final Optional<String> key;

    Http(MockMvc mvc, String header) {
        this(mvc, header, Optional.empty());
    }

    private Http(MockMvc mvc, String header, Optional<String> key) {
        this.mvc = mvc;
        this.header = header;
        this.key = key;
    }

    /** The same client, presenting {@code secret} on every request. */
    public Http as(String secret) {
        return new Http(mvc, header, Optional.of(secret));
    }

    public Answer get(String path) {
        return send(request(HttpMethod.GET, path));
    }

    public Answer post(String path) {
        return send(request(HttpMethod.POST, path));
    }

    public Answer postJson(String path, Object body) {
        return send(request(HttpMethod.POST, path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(write(body)));
    }

    /** A post whose body is raw bytes of the type given. */
    public Answer postBytes(String path, byte[] body, MediaType type) {
        return send(request(HttpMethod.POST, path).contentType(type).content(body));
    }

    public Answer put(String path) {
        return send(request(HttpMethod.PUT, path));
    }

    public Answer putJson(String path, Object body) {
        return send(request(HttpMethod.PUT, path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(write(body)));
    }

    public Answer delete(String path) {
        return send(request(HttpMethod.DELETE, path));
    }

    /**
     * A request for a path that may carry a query string, read the way a servlet container reads
     * one: each name and value percent decoded, with a {@code +} standing for a space.
     */
    private static MockHttpServletRequestBuilder request(HttpMethod method, String path) {
        int mark = path.indexOf('?');
        if (mark < 0) {
            return MockMvcRequestBuilders.request(method, path);
        }
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path.substring(0, mark));
        for (String pair : path.substring(mark + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            request.param(
                    URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return request;
    }

    /** Send a request built by hand, with this client's key. */
    public Answer send(MockHttpServletRequestBuilder request) {
        key.ifPresent(secret -> request.header(header, secret));
        try {
            MvcResult result = mvc.perform(request).andReturn();
            return new Answer(
                    result.getResponse().getStatus(),
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                    result.getResponse());
        } catch (Exception failure) {
            throw new IllegalStateException("the request could not be sent", failure);
        }
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * What came back.
     *
     * @param status the HTTP status
     * @param text the body as text
     * @param response the whole response, for its headers
     */
    public record Answer(int status, String text, org.springframework.mock.web.MockHttpServletResponse response) {

        /** The body, read as JSON. */
        public JsonNode json() {
            try {
                return JSON.readTree(text);
            } catch (JsonProcessingException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        /** One header, if it was sent. */
        public Optional<String> header(String name) {
            return Optional.ofNullable(response.getHeader(name));
        }
    }
}
