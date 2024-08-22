package edu.harvard.hms.dbmi.avillach.auth.model.ras;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class Ga4ghPassportV1 {

    private String iss;
    private String sub;
    private long iat;
    private long exp;
    private String scope;
    private String jti;
    private String txn;

    @JsonProperty("ga4gh_visa_v1")
    private Ga4ghVisaV1 ga4ghVisaV1;

    @JsonProperty("ras_dbgap_permissions")
    private List<RasDbgapPermission> rasDbgagPermissions;

    public String getIss() {
        return iss;
    }

    public void setIss(String iss) {
        this.iss = iss;
    }

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
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

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public String getTxn() {
        return txn;
    }

    public void setTxn(String txn) {
        this.txn = txn;
    }

    public Ga4ghVisaV1 getGa4ghVisaV1() {
        return ga4ghVisaV1;
    }

    public void setGa4ghVisaV1(Ga4ghVisaV1 ga4ghVisaV1) {
        this.ga4ghVisaV1 = ga4ghVisaV1;
    }

    public List<RasDbgapPermission> getRasDbgagPermissions() {
        return rasDbgagPermissions;
    }

    public void setRasDbgagPermissions(List<RasDbgapPermission> rasDbgagPermissions) {
        this.rasDbgagPermissions = rasDbgagPermissions;
    }

    @Override
    public String toString() {
        return "Ga4ghPassportV1{" +
                "iss='" + iss + '\'' +
                ", sub='" + sub + '\'' +
                ", iat=" + iat +
                ", exp=" + exp +
                ", scope='" + scope + '\'' +
                ", jti='" + jti + '\'' +
                ", txn='" + txn + '\'' +
                ", ga4ghVisaV1=" + ga4ghVisaV1 +
                ", rasDbgagPermissions=" + rasDbgagPermissions +
                '}';
    }
}
