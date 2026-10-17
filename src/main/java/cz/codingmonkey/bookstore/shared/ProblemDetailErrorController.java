package cz.codingmonkey.bookstore.shared;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renders the servlet container's error page as a problem detail, replacing Spring Boot's
 * {@code BasicErrorController}. Errors get here when they weren't handled inside Spring MVC: rejections by the
 * security filters (401, 403) and unexpected exceptions (500). Only the status and the request path are reported;
 * the cause's message could expose internals.
 */
@RestController
class ProblemDetailErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatusCode status = statusOf(request);
        ProblemDetail problem = ProblemDetail.forStatus(status);
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path) {
            try {
                problem.setInstance(URI.create(path));
            } catch (IllegalArgumentException notAUri) {
                // Leave the instance out rather than fail while reporting an error.
            }
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** The client accepts no JSON at all: send the status alone, like Spring Boot's error controller. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> notAcceptable(HttpServletRequest request) {
        return ResponseEntity.status(statusOf(request)).build();
    }

    private static HttpStatusCode statusOf(HttpServletRequest request) {
        return request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                ? HttpStatusCode.valueOf(code)
                : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
