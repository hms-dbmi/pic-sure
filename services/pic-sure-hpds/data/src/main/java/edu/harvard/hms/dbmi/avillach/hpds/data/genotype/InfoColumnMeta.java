package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Jacksonized
@Value
@Builder
public class InfoColumnMeta {

    String key, description;
    boolean continuous;
    Float min, max;

}
