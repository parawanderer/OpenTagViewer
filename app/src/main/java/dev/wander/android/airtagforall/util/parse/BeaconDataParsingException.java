package dev.wander.android.airtagforall.util.parse;

public class BeaconDataParsingException extends RuntimeException {
    public BeaconDataParsingException(String message) {
        super(message);
    }

  public BeaconDataParsingException(String message, Throwable cause) {
    super(message, cause);
  }
}
