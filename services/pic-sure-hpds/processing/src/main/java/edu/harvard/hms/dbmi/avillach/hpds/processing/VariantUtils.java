package edu.harvard.hms.dbmi.avillach.hpds.processing;

public class VariantUtils {
    public static boolean pathIsVariantSpec(String key) {
        return key.matches("rs[0-9]+.*") || key.matches(".*,[0-9\\\\.]+,[CATGcatg]*,[CATGcatg]*");
    }
}
