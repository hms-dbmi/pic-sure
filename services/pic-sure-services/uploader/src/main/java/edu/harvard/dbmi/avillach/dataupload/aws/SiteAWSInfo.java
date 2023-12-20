package edu.harvard.dbmi.avillach.dataupload.aws;

public record SiteAWSInfo(String siteName, String roleARN, String externalId, String bucket, String kmsKeyID) {
}
