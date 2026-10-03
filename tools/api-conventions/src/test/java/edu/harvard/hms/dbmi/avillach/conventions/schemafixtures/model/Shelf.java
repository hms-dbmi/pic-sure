package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** A documented class with an inner class, whose compiler-generated reference to the outer instance is not a member. */
@Schema(description = "A shelf of studies")
public class Shelf {

    @Schema(description = "The shelf label", example = "Cardiology")
    private String label;

    @Schema(description = "The first slot on the shelf")
    private Slot first;

    /** A slot that reads its shelf, so the compiler keeps the synthetic outer reference. */
    @Schema(description = "A slot on a shelf")
    public class Slot {

        @Schema(description = "The position of the slot on its shelf", example = "3")
        private int position;

        /** @return the label of the shelf this slot sits on */
        public String shelfLabel() {
            return label;
        }
    }
}
