package examples.auction;

import io.baratine.service.Result;
import io.baratine.service.Service;
import io.baratine.vault.IdAsset;
import io.baratine.vault.Vault;

@Service("/User")
public interface UserAbstractVault<X extends User> extends Vault<IdAsset,X>
{
  void create(AuctionUserSession.UserInitData userInitData,
              Result<IdAsset> result);

  void findByName(String name, Result<User> result);
}
