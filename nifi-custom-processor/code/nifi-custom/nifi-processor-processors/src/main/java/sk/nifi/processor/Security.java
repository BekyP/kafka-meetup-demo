package sk.nifi.processor;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
class Security {

  /**
   * Protocol used to communicate with brokers. Corresponds to Kafka's 'security.protocol'
   * property.
   */
  private String securityProtocol;

  /**
   * Specifies the Kerberos Credentials Controller Service that should be used for
   * authenticating with Kerberos.
   */
  private String saslKerberosServiceName;

  private String saslKerberosPrincipal;

  private String saslKerberosKeytab;
}
