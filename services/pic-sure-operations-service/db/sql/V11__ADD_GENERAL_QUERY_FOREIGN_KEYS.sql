USE `picsure`;

SET @saved_fk_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;

ALTER TABLE `query`
    ADD CONSTRAINT `fk_query_query_to_variant` FOREIGN KEY (`query_to_variant_uuid`) REFERENCES `query_to_variant` (`uuid`),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE `named_dataset`
    ADD CONSTRAINT `fk_named_dataset_query_to_variant` FOREIGN KEY (`query_to_variant_uuid`) REFERENCES `query_to_variant` (`uuid`),
    ALGORITHM=INPLACE, LOCK=NONE;

SET SESSION foreign_key_checks = @saved_fk_checks;
