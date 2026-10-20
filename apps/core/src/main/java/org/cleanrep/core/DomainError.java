package org.cleanrep.core;

final class DomainError extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final String code;
  final int http;
  final transient Object version;
  DomainError(String code) { this(code, 400, null); }
  DomainError(String code, int http) { this(code, http, null); }
  DomainError(String code, int http, Object version) {
    super(Character.toUpperCase(code.charAt(0)) + code.substring(1).replace('_', ' ') + ".");
    this.code = code; this.http = http; this.version = version;
  }
}
