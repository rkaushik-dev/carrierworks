package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Reads the page a listing route was asked for, refusing one outside the bench's limits. */
final class PageResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Page.class;
    }

    @Override
    public Page resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        int limit = whole(request.getParameter("limit"), "limit", Page.DEFAULT_LIMIT);
        int offset = whole(request.getParameter("offset"), "offset", 0);
        if (limit < 1 || limit > Page.MAX) {
            throw new ValidationError("a page holds 1 to " + Page.MAX + " rows", "limit", Details.of("given", limit));
        }
        if (offset < 0) {
            throw new ValidationError("an offset cannot be negative", "offset", Details.of("given", offset));
        }
        return new Page(limit, offset);
    }

    private static int whole(String raw, String field, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException failure) {
            throw new ValidationError("that is not a whole number", field, Details.of("given", raw));
        }
    }
}
