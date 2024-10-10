package edu.harvard.hms.dbmi.avillach.hpds.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
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

    @PostMapping("/patients")
    public Mono<VariantMask> queryForPatientMask(@RequestBody DistributableQuery distributableQuery) {
        return genomicProcessor.getPatientMask(distributableQuery);
    }

    @PostMapping("/variants")
    public Mono<Set<String>> queryForVariants(@RequestBody DistributableQuery distributableQuery) {
        return genomicProcessor.getVariantList(distributableQuery);
    }

    @GetMapping("/patients/ids")
    public List<String> getPatientIds() {
        return genomicProcessor.getPatientIds();
    }

    @GetMapping("/info/columns")
    public Set<String> getInfoStoreColumns() {
        return genomicProcessor.getInfoStoreColumns();
    }

    @GetMapping("/info/values")
    public Set<String> getInfoStoreValues(@RequestParam("conceptPath") String conceptPath) {
        return genomicProcessor.getInfoStoreValues(conceptPath);
    }

    @GetMapping("/info/meta")
    public List<InfoColumnMeta> getInfoMetadata() {
        return genomicProcessor.getInfoColumnMeta();
    }
}
