package org.kendar.dlna.clients;

import java.util.regex.Pattern;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Predicate on a request header. {@code name} is case-insensitive; {@code valueRegex} null means "header present",
 * else the regex must be found (case-insensitive) in one of the values; {@code negate} inverts the predicate.
 */
public record HeaderMatch(String name, String valueRegex, boolean negate) {

    @JsonIgnore
    Pattern compile() {
        return valueRegex == null ? null : Pattern.compile(valueRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
