package sk.softec.events.nifi.processor;

import java.util.UUID;
import java.util.List;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.nifi.logging.ComponentLog;

// TODO najlepsie by bolo pouzit priamo org.apache.nifi.processors.kafka.pubsub.KafkaProcessorUtils a ostatne triedy,
//  spravit toto cele ako nadstavbu

public class KafkaProducerImpl {

  /**
   * Create and setup producers properties and producer.
   * @param brokers brokers where we want to send data
   * @param security security properties necessary for secure kafka
   * @return producer properties
   * @see Security
   */
  private static Producer<String, String> createProducer(String brokers, Security security) {
    Properties props = new Properties();

    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, UUID.randomUUID().toString());
    props.put(ProducerConfig.ACKS_CONFIG, "all"); // Delivery Guarantee = Guarantee Replicated Delivery
    props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, "1048576"); // Max Request Size = 1MB
    props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, "org.apache.kafka.clients.producer.internals.DefaultPartitioner"); // Partitioner class = DefaultPartitioner

    String securityProtocol = security.getSecurityProtocol();
    props.put("security.protocol", security.getSecurityProtocol());
    if ( CDRTPublish.SEC_SASL_PLAINTEXT.getValue().equals(securityProtocol) || CDRTPublish.SEC_SASL_SSL.getValue().equals(securityProtocol) ) {
      setJaasConfig(props, security);
    }

    return new org.apache.kafka.clients.producer.KafkaProducer<>(props);
  }

  /**
   * Method used to configure the 'sasl.jaas.config' property based on KAFKA-4259<br />
   * https://cwiki.apache.org/confluence/display/KAFKA/KIP-85%3A+Dynamic+JAAS+configuration+for+Kafka+clients<br />
   * <br />
   * It expects something with the following format: <br />
   * <br />
   * &lt;LoginModuleClass&gt; &lt;ControlFlag&gt; *(&lt;OptionName&gt;=&lt;OptionValue&gt;); <br />
   * ControlFlag = required / requisite / sufficient / optional
   *
   * @param mapToPopulate Map of configuration properties
   * @param context Context
   */
  private static void setJaasConfig(Properties props, Security security) {
    String keytab = security.getSaslKerberosKeytab();
    String principal = security.getSaslKerberosPrincipal();

    String serviceName = security.getSaslKerberosServiceName();
    if(StringUtils.isNotBlank(keytab) && StringUtils.isNotBlank(principal) && StringUtils.isNotBlank(serviceName)) {
      props.put(SaslConfigs.SASL_JAAS_CONFIG,
                "com.sun.security.auth.module.Krb5LoginModule required "
              + "useTicketCache=false "
              + "renewTicket=true "
              + "serviceName=\"" + serviceName + "\" "
              + "useKeyTab=true "
              + "keyTab=\"" + keytab + "\" "
              + "principal=\"" + principal + "\";");
    }
  }

  /**
   * Take producer and send list of Messages (rows from flowFile) to kafka.
   * @param messageList transformed and standardized rows from flowFile
   * @param messageKey key that will be included in every record
   * @param brokers brokers where we want to send data
   * @param security security properties necessary for secure kafka
   * @param log internal NiFi logger
   * @return status, if all rows have been successfully sent
   */
  static Boolean runProducer(List<Message> messageList, String messageKey, String brokers, Security security, ComponentLog log) {
    final Producer<String, String> producer = createProducer(brokers, security);

    log.trace("Producer has been created. brokers: {}", new String[]{brokers});
    producer.initTransactions();
    try {
      producer.beginTransaction();

      log.debug("Sending to kafka...");
      for (Message message : messageList) {
        final ProducerRecord<String, String> record = new ProducerRecord<>(message.getTopic(), messageKey, message.getRecord());
        producer.send(record);
      }

      producer.commitTransaction();
      return true;
    } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
      // We can't recover from these exceptions, so our only option is to close the producer and exit.
      log.error("Couldn't send messages to Kafka: {}", new String[]{e.getMessage()});
      return false;
    } catch (Exception e){
      producer.abortTransaction();
      log.error("Transaction aborted. Couldn't send messages to Kafka: {}", new String[]{e.getMessage()});
      return false;
    } finally {
      producer.close();
    }
  }
}
