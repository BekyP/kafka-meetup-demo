/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sk.softec.events.osk.nifi.processor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CDRTPublish class contains main parts of processor visible in NiFi environment. Processor is able
 * to read flowFile row by row and transform and standardize its data.
 */

@Tags({"Softec, Orange Slovensko, CDR, CSV, Apache, Kafka, Put, Send, Message, PubSub, 1.1.0"})
@CapabilityDescription("Sends the contents of a FlowFile into Apache Kafka. "
    + "The messages to send are individual lines from provided FlowFiles. "
    + "Lines are first converted from mediation specific CSV format into CSV format "
    + "according to RFC 4180. Additional timestamp is added to the end of line.")
@SeeAlso()
@ReadsAttributes({@ReadsAttribute(attribute = "")})
@WritesAttributes({@WritesAttribute(attribute = "")})
public class CDRTPublish extends AbstractProcessor {

  /**
   * Internal NiFi logger.
   */
  private ComponentLog log;

  /**
   * The value to be added to the end of each row as DATE_DWH_LOAD column.
   */
  private static final PropertyDescriptor TIMESTAMP = new PropertyDescriptor.Builder()
      .name("timestamp")
      .displayName("Timestamp")
      .description("The time value to be added to the end of each row as DATE_DWH_LOAD column. Must use format 'yyyyMMddHHmmss'.")
      .required(true)
      .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
      .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
      .build();

  /**
   * A comma-separated list of known Kafka Brokers in the format {@literal <}host{@literal >}:{@literal <}port{@literal >}
   */
  private static final PropertyDescriptor KAFKA_BROKERS = new PropertyDescriptor.Builder()
      .name("kafka-brokers")
      .displayName("Kafka Brokers")
      .description("A comma-separated list of known Kafka Brokers in the format <host>:<port>")
      .required(true)
      .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
      .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
      .build();

  static final AllowableValue SEC_PLAINTEXT = new AllowableValue("PLAINTEXT", "PLAINTEXT", "PLAINTEXT");
  static final AllowableValue SEC_SSL = new AllowableValue("SSL", "SSL", "SSL");
  static final AllowableValue SEC_SASL_PLAINTEXT = new AllowableValue("SASL_PLAINTEXT", "SASL_PLAINTEXT", "SASL_PLAINTEXT");
  static final AllowableValue SEC_SASL_SSL = new AllowableValue("SASL_SSL", "SASL_SSL", "SASL_SSL");

  private static final PropertyDescriptor SECURITY_PROTOCOL = new PropertyDescriptor.Builder()
          .name("security.protocol")
          .displayName("Security Protocol")
          .description("Protocol used to communicate with brokers. Corresponds to Kafka's 'security.protocol' property.")
          .required(true)
          .expressionLanguageSupported(ExpressionLanguageScope.NONE)
          .allowableValues(SEC_PLAINTEXT, SEC_SSL, SEC_SASL_PLAINTEXT, SEC_SASL_SSL)
          .defaultValue(SEC_PLAINTEXT.getValue())
          .build();

  /**
   * The Kerberos principal name that Kafka runs as. This can be defined either in
   * Kafka's JAAS config or in Kafka's config. Corresponds to Kafka's 'security.protocol'
   * property. It is ignored unless one of the SASL options of the {@literal <}Security Protocol{@literal >} are
   * selected.
   */
  private static final PropertyDescriptor JAAS_SERVICE_NAME = new PropertyDescriptor.Builder()
      .name("sasl.kerberos.service.name")
      .displayName("Kerberos Service Name")
      .description("The service name that matches the primary name of the Kafka server configured in the broker JAAS file."
          + "This can be defined either in Kafka's JAAS config or in Kafka's config. "
          + "Corresponds to Kafka's 'security.protocol' property."
          + "It is ignored unless one of the SASL options of the <Security Protocol> are selected.")
      .required(false)
      .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
      .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
      .build();

  private static final PropertyDescriptor USER_PRINCIPAL = new PropertyDescriptor.Builder()
      .name("sasl.kerberos.principal")
      .displayName("Kerberos Principal")
      .description("The Kerberos principal that will be used to connect to brokers. If not set, it is expected to set a JAAS configuration file "
          + "in the JVM properties defined in the bootstrap.conf file. This principal will be set into 'sasl.jaas.config' Kafka's property.")
      .required(false)
      .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
      .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
      .build();

  private static final PropertyDescriptor USER_KEYTAB = new PropertyDescriptor.Builder()
      .name("sasl.kerberos.keytab")
      .displayName("Kerberos Keytab")
      .description("The Kerberos keytab that will be used to connect to brokers. If not set, it is expected to set a JAAS configuration file "
          + "in the JVM properties defined in the bootstrap.conf file. This principal will be set into 'sasl.jaas.config' Kafka's property.")
      .required(false)
      .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
      .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
      .build();

  /**
   * The string to represented topic prefix name.
   */
  private static final PropertyDescriptor TOPIC_PREFIX = new PropertyDescriptor.Builder()
      .name("topic-prefix")
      .displayName("Topic Prefix")
      .description("A string to be used as a topic name prefix.")
      .required(true)
      .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
      .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
      .build();

  private static final Relationship SUCCESS = new Relationship.Builder()
      .name("success")
      .description("All records from the file has been successfully sent to Kafka")
      .build();

  private static final Relationship FAILURE = new Relationship.Builder()
      .name("failure")
      .description("At least one record from the file has not been successfully sent to Kafka")
      .build();

  private List<PropertyDescriptor> descriptors = Collections
      .unmodifiableList(new ArrayList<>(Arrays
          .asList(TIMESTAMP, TOPIC_PREFIX, KAFKA_BROKERS,
                  SECURITY_PROTOCOL, JAAS_SERVICE_NAME, USER_PRINCIPAL, USER_KEYTAB)));

  private Set<Relationship> relationships = Collections
      .unmodifiableSet(new HashSet<>(Arrays.asList(SUCCESS, FAILURE)));

  /**
   * When a Processor is created, before any other methods are invoked, the init method of the
   * AbstractProcessor will be invoked.
   * @param context Object supplies the Processor with a ComponentLog,
   *   the Processor’s unique identifier, and a ControllerServiceLookup that can be used to interact
   *   with the configured ControllerServices. Each of these objects is stored by the AbstractProcessor
   *   and may be obtained by subclasses via the getLogger, getIdentifier, and getControllerServiceLookup
   *   methods, respectively.
   */
  @Override
  protected void init(final ProcessorInitializationContext context) {
    this.log = context.getLogger();
  }

  @Override
  public Set<Relationship> getRelationships() {
    return this.relationships;
  }

  @Override
  public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
    return descriptors;
  }

  @Override
  public void onTrigger(final ProcessContext context, final ProcessSession session) {
    FlowFile flowFile = session.get();
    if (flowFile == null) {
      log.trace("onTrigger method - flowFile is null");
      return;
    }

    String uuid = flowFile.getAttribute(CoreAttributes.UUID.key());
    String filename = flowFile.getAttribute(CoreAttributes.FILENAME.key());
    log.trace("onTrigger method - flowFile uuid: {}", new String[]{uuid});

    Security security = Security.builder()
        .securityProtocol(
            context.getProperty(SECURITY_PROTOCOL).evaluateAttributeExpressions(flowFile).getValue())
        .saslKerberosServiceName(
            context.getProperty(JAAS_SERVICE_NAME).evaluateAttributeExpressions(flowFile).getValue())
        .saslKerberosPrincipal(
            context.getProperty(USER_PRINCIPAL).evaluateAttributeExpressions(flowFile).getValue())
        .saslKerberosKeytab(
            context.getProperty(USER_KEYTAB).evaluateAttributeExpressions(flowFile).getValue())
        .build();

    log.debug("Security properties have been loaded.");

    MutableBoolean successful = new MutableBoolean();
    String timeStamp = context.getProperty(TIMESTAMP).evaluateAttributeExpressions(flowFile).getValue();
    String brokers = context.getProperty(KAFKA_BROKERS).evaluateAttributeExpressions(flowFile).getValue();
    String homogeneous = flowFile.getAttribute("homogeneous");
    String streamType = flowFile.getAttribute("stream_type");
    String topic = context.getProperty(TOPIC_PREFIX).evaluateAttributeExpressions(flowFile).getValue();

    log.debug("Brokers have been loaded.");
    log.info("Processing file - filename: {}, uuid: {}", new String[]{filename, uuid});

    flowFile = session.write(flowFile, new StreamCallback() {

      @Override
      public void process(InputStream in, OutputStream out) {
        List<Message> messageList = new ArrayList<>();
        try (InputStreamReader inReader = new InputStreamReader(in);
            BufferedReader reader = new BufferedReader(inReader);
            OutputStreamWriter outWriter = new OutputStreamWriter(out);
            BufferedWriter writer = new BufferedWriter(outWriter)) {

          String line = reader.readLine();

          while (line != null) {
            messageList.add(transform(line, topic));

            writer.write(line);
            writer.newLine();

            line = reader.readLine();
          }

          log.debug("File is ready for producer.");
          successful.setValue(
              KafkaProducerImpl.runProducer(messageList, filename, brokers, security, log));
        } catch (IOException | NullPointerException e) {
          log.error("Error occurred: {}", new String[]{e.getMessage()});
        }
      }

      /**
       * Returns Message which is ready for producer to be sent.
       *
       * @param line one row from flowFile
       * @param topic topic fredix
       * @return message which is sent to producer
       * @see Message
       */
      private Message transform(String line, String topic) {
        log.trace("Transform method. line: {}, topic: {}", new String[]{line, topic});

        line = addTimeStamp(line);

        if ("false".equals(homogeneous)) {
          String[] parts = line.split(";", 2);
          topic += parts[0];
          parts = ArrayUtils.remove(parts, 0);
          line = String.join(";", parts);
        } else {
          topic += streamType;
        }
        line = standardize(line);

        log.trace("Line has been transformed. line: {}, topic: {}", new String[]{line, topic});
        return new Message(line, topic.replace("-", "_").toLowerCase());
      }

      /**
       * @param line One row from flowFile
       * @return row with added timestamp.
       */
      private String addTimeStamp(String line) {
        log.trace("AddTimeStamp method. line: {}", new String[]{line});
        return line + ";" + timeStamp;
      }

      /**
       * Returns standardized row for future processing.
       * @param line One row from flowFile
       * @return standardized row
       */
      private String standardize(String line) {
        log.trace("Standardize method. line: {}", new String[]{line});
        line = StringUtils.replace(line, "\"", "\"\"");
        line = StringUtils.replace(line, ";", "\";\"");
        line = StringUtils.replace(line, "\b", ";");
        line = StringUtils.replace(line, "\\n", "\n");
        line = StringUtils.replace(line, "\\\\", "\\");
        return "\"" + line + "\"";
      }
    });

    if (successful.getValue() == null) {
      log.error("Error: KafkaProducer returned null");
      session.transfer(flowFile, FAILURE);
    } else if (successful.getValue()) {
      log.debug("FlowFile has been successfully sent.");
      session.transfer(flowFile, SUCCESS);
    } else {
      log.debug("Processing problem. FlowFile is going to Sleep processor.");
      session.transfer(flowFile, FAILURE);
    }
  }
}
