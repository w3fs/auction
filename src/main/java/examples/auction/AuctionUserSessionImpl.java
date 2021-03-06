package examples.auction;

import java.util.logging.Logger;

import io.baratine.service.Result;
import io.baratine.service.Service;
import io.baratine.web.Body;
import io.baratine.web.Path;
import io.baratine.web.Post;
import io.baratine.web.cors.CrossOrigin;

/**
 * User visible channel facade at session:///auction-session.
 */
@Service("session:///user")
@CrossOrigin(value = "*", allowCredentials = true)
@Path("/user")
public class AuctionUserSessionImpl extends AbstractAuctionSession
  implements AuctionUserSession
{
  private final static Logger log
    = Logger.getLogger(AuctionUserSessionImpl.class.getName());

  @Post("/createAuction")
  public void createAuction(@Body("t") String title,
                            @Body("b") int price,
                            Result<WebAuction> result)
  {
    validateSession();

    _auctions.create(new AuctionDataInit(_userId, title, price),
                     result.then((id, r) -> afterCreateAuction(id.toString(),
                                                               r)));
  }

  private void afterCreateAuction(String auctionId, Result<WebAuction> result)
  {
    Auction auction = _manager.service(Auction.class, auctionId);

    auction.open(result.then((b, r) -> getAuction(auctionId, r)));
  }

  /**
   * Bid on an auction.
   *
   * @param bid    the new bid
   * @param result true for successful auction.
   */
  @Post("/bidAuction")
  public void bidAuction(@Body WebBid bid, Result<Boolean> result)
  {
    validateSession();

    getAuctionService(bid.getAuction())
      .bid(new AuctionBid(_userId, bid.getBid()), result);
  }
}
