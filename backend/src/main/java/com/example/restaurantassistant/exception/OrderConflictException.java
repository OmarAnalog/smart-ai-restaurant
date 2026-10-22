package com.example.restaurantassistant.exception;
public class OrderConflictException extends RuntimeException {
  public final String code;
  public OrderConflictException(String code, String detail) { super(detail); this.code = code; }
}
