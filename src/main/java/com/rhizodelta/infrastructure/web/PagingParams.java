package com.rhizodelta.infrastructure.web;

/**
 * Offset pagination parameters shared by list-style HTTP endpoints.
 */
public record PagingParams(int page, int size, long skip) {
    public static final int MAX_SIZE = 100;
    private static final long MAX_SKIP = Integer.MAX_VALUE;

    public static PagingParams normalize(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }

        long skip = (long) page * size;
        if (skip > MAX_SKIP) {
            throw new IllegalArgumentException("pagination offset is too large");
        }
        return new PagingParams(page, size, skip);
    }
}
