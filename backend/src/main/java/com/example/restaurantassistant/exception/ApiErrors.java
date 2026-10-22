package com.example.restaurantassistant.exception;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice public class ApiErrors {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiErrors.class);
  @ExceptionHandler(LlmException.class) public ResponseEntity<ProblemDetail> llm(LlmException e) {
    log.warn("Chat AI failure: {}",e.getMessage());
    return problem(HttpStatus.BAD_GATEWAY,e.getMessage());
  }
  @ExceptionHandler( {
    MethodArgumentNotValidException.class,HttpMessageNotReadableException.class
  }
  ) public ResponseEntity<ProblemDetail> validation(Exception e) {
    return problem(HttpStatus.BAD_REQUEST,"Check the request fields: valid IDs, nonblank text, and valid quantities or preferences are required.");
  }
  @ExceptionHandler(OrderConflictException.class)
  public ResponseEntity<ProblemDetail> orderConflict(OrderConflictException e) {
    var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    detail.setProperty("code", e.code);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
  }
  @ExceptionHandler({org.springframework.orm.ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
  public ResponseEntity<ProblemDetail> stockConflict(Exception e) {
    var detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Availability changed while placing your order. Review quantities and retry.");
    detail.setProperty("code", "STOCK_CONFLICT");
    return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
  }
  @ExceptionHandler(DataAccessException.class) public ResponseEntity<ProblemDetail> database(DataAccessException e) {
    log.error("Database operation failed: {}",e.getClass().getSimpleName());
    return problem(HttpStatus.SERVICE_UNAVAILABLE,"Database operation could not be completed. Please retry.");
  }
  private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,detail));
  }
}
