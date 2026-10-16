package org.kendar.dlna.curation;

/** The typed confirmation that destructive admin actions (bulk tag edit, directory delete) require, checked server-side. */
public final class Consent {

    public static final String PHRASE = "I CONSENT";

    private Consent() {
    }

    /** Throws {@link CurationException#INVALID} unless {@code confirm} is exactly {@value #PHRASE}. */
    public static void require(String confirm) {
        if (!PHRASE.equals(confirm)) {
            throw new CurationException(CurationException.INVALID,
                    "Confirmation required: send confirm=\"" + PHRASE + "\" (all capitals)");
        }
    }
}
