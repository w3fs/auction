package examples.auction;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.caucho.v5.ramp.jamp.WebJamp;
import static io.baratine.web.Web.*;

public class Main
{
  public static void main(String[] args)
  {
    //property("server.file", "classpath:/public");
    property("server.file", "/Users/alex/projects/baratine-github/auction");

    include(AuctionAdminSessionImpl.class);
    include(AuctionSessionImpl.class);

    include(AuctionSettlementVault.class);
    include(AuditServiceImpl.class);
    include(PayPalImpl.class);

    include(UserVault.class);
    include(AuctionVault.class);

    route("/jamp").to(WebJamp.class);

    Level level = Level.FINEST;

    Logger.getLogger("com.caucho").setLevel(level);
    Logger.getLogger("examples").setLevel(level);
    Logger.getLogger("core").setLevel(level);

    try {
      start();
    } catch (Exception e) {
      e.printStackTrace();
    }

    Logger.getLogger("com.caucho").setLevel(level);
    Logger.getLogger("examples").setLevel(level);
    Logger.getLogger("core").setLevel(level);
  }
}
