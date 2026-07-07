package edu.harvard.dbmi.avillach.dump.local;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;

import java.time.LocalDateTime;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonSerialize
public record UpdateInfo(@JsonSerialize(using = LocalDateSerializer.class) LocalDateTime lastUpdate) {
}
