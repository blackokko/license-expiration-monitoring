package com.compsych.licmon.domain;

import java.time.Instant;

public record Provider(String npi, String firstName, String lastName, Instant updatedAt) {
}
