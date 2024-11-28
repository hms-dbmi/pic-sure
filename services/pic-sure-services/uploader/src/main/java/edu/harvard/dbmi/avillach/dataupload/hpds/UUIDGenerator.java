package edu.harvard.dbmi.avillach.dataupload.hpds;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UUIDGenerator {

    public UUID generate() {
        return UUID.randomUUID();
    }
}
