package cz.codingmonkey.bookstore.shared;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Answers failed requests with RFC 9457 problem details ({@code application/problem+json}).
 * <p>
 * Spring MVC's own errors (unreadable body, wrong parameter type, unsupported method,
 * {@code ResponseStatusException}, ...) are handled by {@link ResponseEntityExceptionHandler}. Module exceptions
 * declare their status with {@link ResponseStatus}, and their message becomes the {@code detail}, so it must be
 * written for API clients. Validation failures also list each invalid field or parameter under {@code errors}.
 * <p>
 * Any other exception is passed on unchanged: it may be meant for another handler (e.g. Spring Security's), and an
 * unexpected one ends up as a 500 from {@link ProblemDetailErrorController}, which doesn't expose its message.
 * Controllers keep their own {@code @ExceptionHandler}s (they take precedence) for errors that only make sense
 * there, such as a concurrent change; those return a {@link ProblemDetail} as well.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    /** One invalid field or parameter of a request. */
    record InvalidValue(String field, @Nullable String message) {
    }

    @ExceptionHandler
    ProblemDetail withDeclaredStatus(RuntimeException ex) {
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (status == null) {
            // Rethrowing the same exception lets the remaining exception resolvers handle it.
            throw ex;
        }
        return ProblemDetail.forStatusAndDetail(status.code(), status.reason().isEmpty() ? ex.getMessage()
                : status.reason());
    }

    /** A {@code @Valid} request body with invalid fields. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidValue> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> invalid(error instanceof FieldError field ? field.getField() : error.getObjectName(),
                        error))
                .toList();
        ex.getBody().setProperty("errors", errors);
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    /** Constraints on controller method parameters, e.g. {@code @Min} on a request parameter. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidValue> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bean) {
                bean.getFieldErrors().forEach(error -> errors.add(invalid(error.getField(), error)));
            } else {
                String parameter = String.valueOf(result.getMethodParameter().getParameterName());
                result.getResolvableErrors().forEach(error -> errors.add(invalid(parameter, error)));
            }
        }
        ex.getBody().setProperty("errors", errors);
        return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }

    private static InvalidValue invalid(String field, MessageSourceResolvable error) {
        return new InvalidValue(field, error.getDefaultMessage());
    }
}
