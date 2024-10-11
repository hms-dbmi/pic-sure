package edu.harvard.hms.dbmi.avillach.auth.model.ras;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The RAS Passport JWT as defined by RAS in their developer documentation.
 * Contains a list of claims about the user and the user's identity and a list of GA4Gh Passports as defined in the GA4GH standard.
 */
public class Passport {

    private String sub;
    private String jti;
    private String scope;
    private String txn;
    private String iss;
    private long iat;
    private long exp;

    @JsonProperty("ga4gh_passport_v1")
    private List<String> ga4ghPassportV1;

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getTxn() {
        return txn;
    }

    public void setTxn(String txn) {
        this.txn = txn;
    }

    public String getIss() {
        return iss;
    }

    public void setIss(String iss) {
        this.iss = iss;
    }

    public long getIat() {
        return iat;
    }

    public void setIat(long iat) {
        this.iat = iat;
    }

    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = exp;
    }

    public List<String> getGa4ghPassportV1() {
        return ga4ghPassportV1;
    }

    public void setGa4ghPassportV1(List<String> ga4ghPassportV1) {
        this.ga4ghPassportV1 = ga4ghPassportV1;
    }

    @Override
    public String toString() {
        return "Passport{" +
                "exp=" + exp +
                ", iat=" + iat +
                ", iss='" + iss + '\'' +
                ", txn='" + txn + '\'' +
                ", scope='" + scope + '\'' +
                ", jti='" + jti + '\'' +
                ", sub='" + sub + '\'' +
                '}';
    }
}
