package com.example.ksql.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Example class that demonstrates how to unit test UDFs.
 */
public class UpdateStateTest {

  @ParameterizedTest(name = "update_state({0})= {1}")
  @CsvSource({
      "incoming, debit",
      "debit, credit",
      "credit, complete",
  })
  void reverseString(final String currentState, final String expectedResult) {
    final UpdateState updateState = new UpdateState();
    final String actualResult = updateState.updateState(currentState);
    assertEquals(expectedResult, actualResult,
        currentState + " reversed should equal " + expectedResult);
  }
}
