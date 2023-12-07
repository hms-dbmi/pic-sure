package edu.harvard.hms.dbmi.avillach.hpds.genomic;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.processing.DistributableQuery;
import edu.harvard.hms.dbmi.avillach.hpds.processing.GenomicProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;


@RestController
public class GenomicProcessorController {

    private GenomicProcessor genomicProcessor;

    @Autowired
    public GenomicProcessorController(GenomicProcessor genomicProcessor) {
        this.genomicProcessor = genomicProcessor;
    }

    @PostMapping("/patients")
    public Mono<BigInteger> queryForPatientMask(@RequestBody DistributableQuery distributableQuery) throws InterruptedException {
        return genomicProcessor.getPatientMask(distributableQuery);
    }

    @PostMapping("/variants")
    public Mono<Collection<String>> queryForVariants(@RequestBody DistributableQuery distributableQuery) {
        return genomicProcessor.getVariantList(distributableQuery);
    }

    @GetMapping("/patients/ids")
    public List<String> getPatientIds() {
        return genomicProcessor.getPatientIds();
    }

    @GetMapping("/info/meta")
    public List<InfoColumnMeta> getInfoMetadata() {
        return genomicProcessor.getInfoColumnMeta();
    }
}
