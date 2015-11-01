package examples.auction;

import javax.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class PayPalRestLink
{
  private static final Logger log
    = Logger.getLogger(PayPalRestLink.class.getName());

  private String _app;
  private String _account;
  private String _clientId;
  private String _secret;
  private String _endpoint;

  public PayPalRestLink() throws IOException
  {
    InputStream in = null;

    try {
      in = PayPalRestLink.class.getResourceAsStream("/paypal.properties");
    } catch (Throwable t) {
      log.log(Level.WARNING, t.getMessage(), t);
    }

    if (in == null) {
      in = new FileInputStream(System.getProperty("user.home")
                               + File.separator
                               + ".paypal.properties");
    }

    try {
      Properties p = new Properties();

      p.load(in);
      _app = p.getProperty("app");
      _account = p.getProperty("account");
      _clientId = p.getProperty("client-id").trim();
      _secret = p.getProperty("secret").trim();
      _endpoint = p.getProperty("endpoint");
    } finally {
      in.close();
    }
  }

  private String getBA() throws UnsupportedEncodingException
  {
    String auth = _clientId + ':' + _secret;
    byte[] bytes = Base64.getEncoder().encode(auth.getBytes("UTF-8"));

    return new String(bytes, "UTF-8");
  }

  public PayPalAuth auth() throws IOException
  {
    Map<String,String> headers = new HashMap<>();

    headers.put("Authorization", "Basic " + getBA());
    headers.put("Accept", "application/json");
    headers.put("Accept-Language", "en_US");
    headers.put("Content-Type", "application/x-www-form-urlencoded");

    String reply = send("/v1/oauth2/token",
                        "POST",
                        headers,
                        "grant_type=client_credentials".getBytes());

    return new PayPalAuth(reply);
  }

  private String send(String subUrl,
                      String method,
                      Map<String,String> headers,
                      byte[] body)
    throws IOException
  {
    if (!subUrl.startsWith("/"))
      throw new IllegalArgumentException();

    URL url = new URL(String.format("https://%1$s", _endpoint) + subUrl);

    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod(method);

    connection.setDoInput(true);
    connection.setDoOutput(true);

    for (Map.Entry<String,String> entry : headers.entrySet()) {
      connection.setRequestProperty(entry.getKey(), entry.getValue());
    }

    if (body != null) {
      OutputStream out = connection.getOutputStream();

      out.write(body);

      out.flush();
    }

    int responseCode = connection.getResponseCode();

    if (responseCode == 200 || responseCode == 201) {
      InputStream in = connection.getInputStream();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] temp = new byte[1024];
      int l;

      while ((l = in.read(temp)) > 0)
        buffer.write(temp, 0, l);

      return new String(buffer.toByteArray());
    }
    else {
      System.out.println(connection.getResponseCode());
      InputStream err = connection.getErrorStream();
      int i;
      while ((i = err.read()) > 0)
        System.out.print((char) i);

      throw new IllegalStateException(connection.getResponseMessage()
                                      + ": "
                                      + connection.getResponseMessage());
    }
  }

  /**
   * @param idempotencyKey
   * @return
   * @throws IOException
   */
  public Payment pay(String securityToken,
                     String idempotencyKey,
                     String ccNumber,
                     String ccType,
                     int ccExpireM,
                     int ccExpireY,
                     String ccv2,
                     String firstName,
                     String lastName,
                     String total,
                     String currency,
                     String description
  ) throws IOException
  {

    final String payment;

    try (InputStream in
           = PayPalRestLink.class.getResourceAsStream("/payment.template.json")) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();

      byte[] bytes = new byte[512];

      int l;

      while ((l = in.read(bytes)) > 0)
        buffer.write(bytes, 0, l);

      payment = String.format(new String(buffer.toByteArray(), "UTF-8"),
                              ccNumber,
                              ccType,
                              ccExpireM,
                              ccExpireY,
                              ccv2,
                              firstName,
                              lastName,
                              total,
                              currency,
                              description);

      System.out.println(payment);
    }

    Map<String,String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Authorization", "Bearer " + securityToken);
    headers.put("PayPal-Request-Id", idempotencyKey);

    String response = send("/v1/payments/payment", "POST", headers,
                           payment.getBytes("UTF-8"));

    return new Payment(response);
  }

  public String list(String token) throws IOException
  {
    Map<String,String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Authorization", "Bearer " + token);

    return send("/v1/payments/payment?count=20", "GET", headers, null);
  }

  @Override
  public String toString()
  {
    return "TestLogin["
           + _app + ", "
           + _account + ", "
           + _clientId + ", "
           + _secret + ", "
           + _endpoint
           + ']';
  }
}