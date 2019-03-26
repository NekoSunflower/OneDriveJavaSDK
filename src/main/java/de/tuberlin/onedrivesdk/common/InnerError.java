package de.tuberlin.onedrivesdk.common;

/**
 * Data object for json transport
 */
public class InnerError {
    private String     code;
    private String     message;
    private String     target;
    private InnerError details;
    private InnerError innerError;

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getTarget() {
        return target;
    }

    public InnerError getDetails() {
        return details;
    }

    public InnerError getInnerError() {
        return innerError;
    }

    @Override
    public String toString() {
        return "code='" + code + '\'' +
                ", message='" + message + '\'' +
                ((target != null) ? ", target='" + target + '\'' : "") +
                ((details != null) ? ", details: {" + details + "}" : "") +
                ((innerError != null) ? ", innerError: {" + innerError + "}" : "");
    }
}
