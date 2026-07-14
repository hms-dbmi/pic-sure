package edu.harvard.hms.dbmi.avillach.hpds.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Set;


@RestController
public class GenomicProcessorController {

    private GenomicProcessor genomicProcessor;

    @Autowired
    public GenomicProcessorController(GenomicProcessor genomicProcessor) {
        this.genomicProcessor = genomicProcessor;
    }

    @AuditEvent(type = "QUERY", action = "genomic.patient.mask")
    @PostMapping("/patients")
    public Mono<VariantMask> queryForPatientMask(@RequestBody DistributableQuery distributableQuery) {
        return genomicProcessor.getPatientMask(distributableQuery);
    }

    @AuditEvent(type = "QUERY", action = "genomic.variant.list")
    @PostMapping("/variants")
    public Mono<Set<String>> queryForVariants(@RequestBody DistributableQuery distributableQuery) {
        return genomicProcessor.getVariantList(distributableQuery);
    }

    @AuditEvent(type = "DATA_ACCESS", action = "genomic.patient.ids")
    @GetMapping("/patients/ids")
    public List<String> getPatientIds() {
        return genomicProcessor.getPatientIds();
    }

    @AuditEvent(type = "OTHER", action = "genomic.info")
    @GetMapping("/info/columns")
    public Set<String> getInfoStoreColumns() {
        return genomicProcessor.getInfoStoreColumns();
    }

    @AuditEvent(type = "OTHER", action = "genomic.info")
    @GetMapping("/info/values")
    public Set<String> getInfoStoreValues(@RequestParam("conceptPath") String conceptPath) {
        return genomicProcessor.getInfoStoreValues(conceptPath);
    }

    @AuditEvent(type = "OTHER", action = "genomic.info")
    @GetMapping("/info/meta")
    public List<InfoColumnMeta> getInfoMetadata() {
        return genomicProcessor.getInfoColumnMeta();
    }
}
