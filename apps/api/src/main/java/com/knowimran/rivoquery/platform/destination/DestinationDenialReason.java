package com.knowimran.rivoquery.platform.destination;

/**
 * Safe, non-leaking classification of why a destination could not be vetted. Used for internal
 * decisioning, audit, and tests; never surfaced verbatim with raw driver/network detail to a
 * client (see {@code docs/CODE_REVIEW.md}).
 */
public enum DestinationDenialReason {
    INVALID_ENDPOINT,
    EMPTY_ANSWER,
    MALFORMED_RESPONSE,
    TRUNCATED_RESPONSE,
    RESOLVER_TIMEOUT,
    INDETERMINATE_RESPONSE,
    CHAIN_TOO_DEEP,
    DEADLINE_EXCEEDED,
    PROHIBITED_ADDRESS,
    /** A pooled binding's previously vetted address set or policy version is no longer current. */
    BINDING_STALE
}
