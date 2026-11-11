package cl.barriodigital.ms_audit.exception;

/** Only fixed diagnostic codes, never raw payloads or infrastructure exceptions. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String code) { super(code); }
}
