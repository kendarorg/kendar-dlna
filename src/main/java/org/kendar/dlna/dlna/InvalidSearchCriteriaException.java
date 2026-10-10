package org.kendar.dlna.dlna;

/** Malformed UPnP search criteria (reported to the control point as error 708). */
public final class InvalidSearchCriteriaException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidSearchCriteriaException(String message) {
        super(message);
    }
}
