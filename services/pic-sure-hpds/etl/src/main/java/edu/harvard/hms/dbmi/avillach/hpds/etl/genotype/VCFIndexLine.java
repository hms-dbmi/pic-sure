package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.util.Objects;

public class VCFIndexLine implements Comparable<VCFIndexLine> {
    private String vcfPath;
    private String contig;
    private boolean isAnnotated;
    private boolean isGzipped;
    private String[] sampleIds;
    private Integer[] patientIds;

    public String getVcfPath() {
        return vcfPath;
    }

    public String getContig() {
        return contig;
    }

    public boolean isAnnotated() {
        return isAnnotated;
    }

    public boolean isGzipped() {
        return isGzipped;
    }

    public String[] getSampleIds() {
        return sampleIds;
    }

    public Integer[] getPatientIds() {
        return patientIds;
    }

    public VCFIndexLine setVcfPath(String vcfPath) {
        this.vcfPath = vcfPath;
        return this;
    }

    public VCFIndexLine setContig(String contig) {
        this.contig = contig;
        return this;
    }

    public VCFIndexLine setAnnotated(boolean annotated) {
        isAnnotated = annotated;
        return this;
    }

    public VCFIndexLine setGzipped(boolean gzipped) {
        isGzipped = gzipped;
        return this;
    }

    public VCFIndexLine setSampleIds(String[] sampleIds) {
        this.sampleIds = sampleIds;
        return this;
    }

    public VCFIndexLine setPatientIds(Integer[] patientIds) {
        this.patientIds = patientIds;
        return this;
    }

    @Override
    public int compareTo(VCFIndexLine o) {
        int chomosomeComparison = o.contig == null ? 1 : contig == null ? 0 : contig.compareTo(o.contig);
        if (chomosomeComparison == 0) {
            return vcfPath.compareTo(o.vcfPath);
        }
        return chomosomeComparison;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VCFIndexLine that = (VCFIndexLine) o;
        return Objects.equals(vcfPath, that.vcfPath) && Objects.equals(contig, that.contig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vcfPath, contig);
    }
}
