package com.example.ksql.functions;

import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;

@UdfDescription(
    name = "update_state",
    description = "Example UDF updates payment state",
    version = "1.0-SNAPSHOT",
    author = "bekap"
)
public class UpdateState {

  private static final String INCOMING_STATE = "incoming";
  private static final String DEBIT_STATE = "debit";
  private static final String CREDIT_STATE = "credit";
  private static final String COMPLETED_STATE = "complete";

  @Udf(description = "Updates payment state")
  public String updateState(
      @UdfParameter(value = "current_state", description = "the value to reverse") final String currentState) {
    switch(currentState) {
      case INCOMING_STATE:
        return DEBIT_STATE;
      case DEBIT_STATE:
        return CREDIT_STATE;
      case CREDIT_STATE:
      case COMPLETED_STATE:
        return COMPLETED_STATE;
      default:
        throw new IllegalStateException("Unexpected value: " + currentState);
    }

  }
}
