package sk.softec.events.osk.nifi.processor;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
class Message {

  /**
   * Transformed and standardized row from flowFile
   */
  private String record;

  /**
   * Topic name where data will be send
   */
  private String topic;
}
