package examples.auction;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.baratine.web.ServiceWebSocket;
import io.baratine.web.WebSocket;

import com.caucho.junit.ConfigurationBaratine;
import com.caucho.junit.HttpClient;
import com.caucho.junit.ServiceTest;
import com.caucho.junit.State;
import com.caucho.junit.TestTime;
import com.caucho.junit.WebRunnerBaratine;
import com.caucho.v5.websocket.WebSocketClient;

import examples.auction.AuctionSession.UserInitData;
import examples.auction.AuctionSession.WebAuction;
import examples.auction.AuctionSession.WebUser;
import examples.auction.AuctionUserSession.WebBid;
import examples.auction.mock.MockPayPal;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(WebRunnerBaratine.class)
@ServiceTest(UserVault.class)
@ServiceTest(AuctionVault.class)
@ServiceTest(AuctionUserSessionImpl.class)
@ServiceTest(AuditServiceImpl.class)
@ServiceTest(AuctionSettlementVault.class)
@ServiceTest(MockPayPal.class)
@ConfigurationBaratine(workDir = "/tmp/baratine")
public class AuctionUserSessionWebTest
{
  public final static String sessionA = "aaaaa";
  public final static String sessionB = "abbbb";
  public final static String sessionC = "acccc";

  @Test
  public void testUserCreate(HttpClient client) throws IOException
  {
    WebUser user = userCreate(client, sessionA, "Spock", "passwd", false);

    Assert.assertEquals("Spock", user.getName());
  }

  private WebUser userCreate(HttpClient client,
                             String session,
                             final String name,
                             final String passwd,
                             final boolean isAdmin) throws IOException
  {
    HttpClient.Response response
      = client.post("/user/createUser")
              .session(session)
              .body(new UserInitData(name, passwd, isAdmin))
              .go();

    Assert.assertEquals(200, response.status());

    WebUser user = response.readObject(WebUser.class);

    return user;
  }

  @Test
  public void testUserLogin(HttpClient client) throws IOException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "passwd");

    Assert.assertTrue(isLoggedIn);
  }

  private boolean userLogin(HttpClient client,
                            String session,
                            final String name,
                            final String passwd) throws IOException
  {
    HttpClient.Response response
      = client.post("/user/login")
              .session(session)
              .body(String.format("u=%1$s&p=%2$s", name, passwd))
              .type("application/x-www-form-urlencoded")
              .go();

    Assert.assertEquals(200, response.status());

    boolean isLoggedIn = response.readObject(Boolean.class);

    return isLoggedIn;
  }

  @Test
  public void testUserLoginFail(HttpClient client) throws IOException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "bogus");

    Assert.assertFalse(isLoggedIn);
  }

  @Test
  public void testAuctionCreate(HttpClient client) throws IOException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "passwd");

    Assert.assertTrue(isLoggedIn);

    WebAuction auction = auctionCreate(client, sessionA, "book", 15);

    Assert.assertEquals("WebAuction[book, 15, OPEN]", String.valueOf(auction));
  }

  private WebAuction auctionCreate(HttpClient client,
                                   String session,
                                   String title,
                                   int price)
    throws IOException
  {
    HttpClient.Response response
      = client.post("/user/createAuction")
              .session(session)
              .body(String.format("t=%1$s&b=%2$d", title, price))
              .type("application/x-www-form-urlencoded")
              .go();

    Assert.assertEquals(200, response.status());

    WebAuction auction = response.readObject(WebAuction.class);

    return auction;
  }

  @Test
  public void testAuctionBid(HttpClient client) throws IOException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "passwd");

    Assert.assertTrue(isLoggedIn);

    WebAuction auction = auctionCreate(client, sessionA, "book", 15);

    Assert.assertEquals("WebAuction[book, 15, OPEN]", String.valueOf(auction));

    userCreate(client, sessionB, "Kirk", "pass", false);
    userLogin(client, sessionB, "Kirk", "pass");

    boolean isAccepted = auctionBid(client, sessionB, auction.getId(), 17);

    Assert.assertTrue(isAccepted);
  }

  private boolean auctionBid(HttpClient client,
                             String session,
                             String auction,
                             int bid)
    throws IOException
  {
    HttpClient.Response response
      = client.post("/user/bidAuction")
              .session(session)
              .body(new WebBid(auction, bid))
              .go();

    Assert.assertEquals(200, response.status());

    boolean isAccepted = response.readObject(Boolean.class);

    return isAccepted;
  }

  @Test
  public void testAuctionBidReject(HttpClient client) throws IOException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "passwd");

    Assert.assertTrue(isLoggedIn);

    WebAuction auction = auctionCreate(client, sessionA, "book", 15);

    Assert.assertEquals("WebAuction[book, 15, OPEN]", String.valueOf(auction));

    userCreate(client, sessionB, "Kirk", "pass", false);
    userLogin(client, sessionB, "Kirk", "pass");

    boolean isAccepted = auctionBid(client, sessionB, auction.getId(), 13);

    Assert.assertFalse(isAccepted);
  }

  @Test
  public void testSearchActions(HttpClient client) throws IOException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "passwd");

    Assert.assertTrue(isLoggedIn);

    auctionCreate(client, sessionA, "book", 15);

    final WebAuction[] books = auctionSearch(client, sessionA, "book");

    Assert.assertEquals("[WebAuction[book, 15, OPEN]]",
                        Arrays.asList(books).toString());
  }

  private WebAuction[] auctionSearch(HttpClient client,
                                     String session,
                                     String query)
    throws IOException
  {
    HttpClient.Response response
      = client.get("/user/searchAuctions?q=" + query)
              .session(session)
              .go();

    Assert.assertEquals(200, response.status());

    WebAuction[] auctions = response.readObject(WebAuction[].class);

    return auctions;
  }

  @Test
  public void testAuctionEvents(HttpClient client)
    throws IOException, InterruptedException
  {
    userCreate(client, sessionA, "Spock", "passwd", false);

    boolean isLoggedIn = userLogin(client, sessionA, "Spock", "passwd");

    Assert.assertTrue(isLoggedIn);

    WebAuction auction = auctionCreate(client, sessionA, "book", 15);

    Assert.assertEquals("WebAuction[book, 15, OPEN]", String.valueOf(auction));

    auctionSubscribe(client, sessionA, auction);

    AuctionUpdatesListener auctionUpdatesListener
      = auctionUpdatesListener(sessionA);

    userCreate(client, sessionB, "Kirk", "pass", false);
    userLogin(client, sessionB, "Kirk", "pass");

    boolean isAccepted = auctionBid(client, sessionB, auction.getId(), 17);

    State.sleep(100);

    Assert.assertTrue(isAccepted);

    String state
      = auctionUpdatesListener.getState().replaceAll(
      "\\\"id\":\\\"[a-zA-Z0-9\\-]+\\\"",
      "\"id\":\"xxx\"");

    Assert.assertEquals(
      "{\"bid\":17,\"id\":\"xxx\",\"state\":\"OPEN\",\"title\":\"book\"}",
      state);

    TestTime.addTime(15000, TimeUnit.MILLISECONDS);
    Thread.sleep(100);

    TestTime.addTime(1000, TimeUnit.MILLISECONDS);
    Thread.sleep(100);

    state = auctionUpdatesListener.getState().replaceAll(
      "\\\"id\":\\\"[a-zA-Z0-9\\-]+\\\"",
      "\"id\":\"xxx\"");

    Assert.assertEquals(
      "{\"bid\":17,\"id\":\"xxx\",\"state\":\"CLOSED\",\"title\":\"book\"}\n"
      + "{\"bid\":17,\"id\":\"xxx\",\"state\":\"SETTLED\",\"title\":\"book\"}",
      state);
  }

  private AuctionUpdatesListener auctionUpdatesListener(String session)
    throws IOException
  {
    AuctionUpdatesListener auctionUpdatesListener
      = new AuctionUpdatesListener();

    WebSocketClient ws
      = WebSocketClient.newClient("ws://localhost:8080/user/auction-updates")
                       .cookie("JSESSIONID", session)
                       .service(auctionUpdatesListener).build();

    return auctionUpdatesListener;
  }

  boolean auctionSubscribe(HttpClient client,
                           String session,
                           WebAuction auction) throws IOException
  {
    HttpClient.Response response
      = client.post("/user/addAuctionListener")
              .session(session)
              .body(auction.getId())
              .go();

    Assert.assertEquals(200, response.status());

    boolean isSuccess = response.readObject(Boolean.class);

    return isSuccess;
  }

  class AuctionUpdatesListener implements ServiceWebSocket<String,String>
  {
    private AtomicReference<StringBuilder> _state
      = new AtomicReference<>(new StringBuilder());

    @Override
    public void next(String s, WebSocket<String> webSocket) throws IOException
    {
      StringBuilder state = _state.get();

      if (state.length() > 0)
        state.append('\n');

      state.append(s);
    }

    public String getState()
    {
      StringBuilder state = _state.getAndSet(new StringBuilder());

      return state.toString();
    }
  }
}
