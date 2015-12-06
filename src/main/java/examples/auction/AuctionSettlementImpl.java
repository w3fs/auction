package examples.auction;

import examples.auction.SettlementTransactionState.AuctionUpdateState;
import examples.auction.SettlementTransactionState.PaymentTxState;
import examples.auction.SettlementTransactionState.UserUpdateState;
import io.baratine.core.Journal;
import io.baratine.core.Modify;
import io.baratine.core.OnInit;
import io.baratine.core.OnLoad;
import io.baratine.core.OnSave;
import io.baratine.core.Result;
import io.baratine.core.ServiceManager;
import io.baratine.core.ServiceRef;
import io.baratine.db.Cursor;
import io.baratine.db.DatabaseService;

import java.util.logging.Logger;

@Journal()
public class AuctionSettlementImpl
  implements AuctionSettlement
{
  private final static Logger log
    = Logger.getLogger(AuctionSettlementImpl.class.getName());

  private DatabaseService _db;
  private PayPal _payPal;
  private ServiceRef _userManager;
  private ServiceRef _auctionManager;

  private BoundState _boundState = BoundState.UNBOUND;

  private String _id;
  private String _auctionId;
  private String _userId;
  private AuctionDataPublic.Bid _bid;

  private SettlementTransactionState _state;

  public AuctionSettlementImpl(String id)
  {
    _id = id;
  }

  //id, auction_id, user_id, bid
  @OnLoad
  public void load(Result<Boolean> result)
  {
    if (_boundState != BoundState.UNBOUND)
      throw new IllegalStateException();

    Result.Fork<Boolean,Boolean> fork = result.newFork();

    fork.fail((l, e, r) -> {
      for (Throwable t : e) {
        if (t != null) {
          r.fail(t);

          break;
        }
      }
    });

    _db.findLocal(
      "select auction_id, user_id, bid from settlement where id = ?",
      _id).first().result(fork.fork().from(c -> loadSettlement(c)));

    _db.findLocal("select state from settlement_state where id = ?",
                  _id).first().result(fork.fork().from(c -> loadState(c)));

    fork.join(l -> l.get(0) && l.get(1));
  }

  public boolean loadSettlement(Cursor settlement)
  {
    if (settlement != null) {
      _auctionId = settlement.getString(1);

      _userId = settlement.getString(2);

      _bid = (AuctionDataPublic.Bid) settlement.getObject(3);

      _boundState = BoundState.BOUND;
    }

    return true;
  }

  public boolean loadState(Cursor state)
  {
    if (state != null)
      _state = (SettlementTransactionState) state.getObject(1);

    if (_state == null)
      _state = new SettlementTransactionState();

    return true;
  }

  @OnInit
  public void init(Result<Boolean> result)
  {
    ServiceManager manager = ServiceManager.current();

    _db = manager.lookup("bardb:///").as(DatabaseService.class);

    _payPal = manager.lookup("pod://auction/paypal").as(PayPal.class);
    _userManager = manager.lookup("pod://user/user");
    _auctionManager = manager.lookup("pod://auction/auction");

    result.complete(true);
  }

  @Override
  @Modify
  public void create(String auctionId,
                     String userId,
                     AuctionDataPublic.Bid bid,
                     Result<Boolean> result)
  {
    log.finer(String.format("create %1$s", this));

    if (_boundState != BoundState.UNBOUND)
      throw new IllegalStateException();

    _auctionId = auctionId;
    _userId = userId;
    _bid = bid;

    _boundState = BoundState.NEW;

    result.complete(true);
  }

  @Modify
  @Override
  public void commit(Result<Status> status)
  {
    log.finer(String.format("commit settlement %1$s", this));

    if (_boundState == BoundState.UNBOUND)
      throw new IllegalStateException();

    commitImpl(status);
  }

  public void commitImpl(Result<Status> status)
  {
    if (_state.isRollingBack())
      throw new IllegalStateException();
    else if (_state.isCommitted())
      throw new IllegalStateException();
    else if (!_state.isCommitting())
      throw new IllegalStateException();

    commitPending(status.from(x -> processCommit(x)));
  }

  public void commitPending(Result<Boolean> status)
  {
    Result.Fork<Boolean,Boolean> fork = status.newFork();

    fork.fail((x, e, r) -> {
      for (Throwable t : e) {
        if (t != null) {
          r.fail(t);

          break;
        }
      }
    });

    updateUser(fork.fork());
    updateAuction(fork.fork());
    chargeUser(fork.fork());

    fork.join(l -> l.get(0) && l.get(1) && l.get(2));
  }

  public void updateUser(Result<Boolean> status)
  {
    User user = getUser();

    user.addWonAuction(_auctionId,
                       status.from(x -> {
                         _state.setUserCommitState(
                           x ?
                             UserUpdateState.SUCCESS :
                             UserUpdateState.REJECTED);
                         return x;
                       }));
  }

  private User getUser()
  {
    User user = _userManager.lookup('/' + _userId).as(User.class);

    return user;
  }

  public void updateAuction(Result<Boolean> status)
  {
    Auction auction = getAuction();

    auction.setAuctionWinner(_userId, status.from(x -> {
      _state.setAuctionCommitState(
        x ?
          AuctionUpdateState.SUCCESS :
          AuctionUpdateState.REJECTED);
      return x;
    }));
  }

  private Auction getAuction()
  {
    Auction auction = _auctionManager.lookup('/' + _auctionId)
                                     .as(Auction.class);

    return auction;
  }

  public void chargeUser(Result<Boolean> status)
  {
    User user = getUser();
    Auction auction = getAuction();

    final ValueRef<AuctionDataPublic> auctionData = new ValueRef();
    final ValueRef<CreditCard> creditCard = new ValueRef();

    Result<Boolean> paymentResult = Result.from(x ->
                                                  chargeUser(auctionData.get(),
                                                             creditCard.get(),
                                                             status),
                                                e -> status.complete(false)
    );

    Result.Fork<Boolean,Boolean> fork = paymentResult.newFork();

    fork = fork.fail((l, e, r) -> {
      for (Throwable t : e) {
        if (t != null) {
          r.fail(t);
          break;
        }
      }
    });

    auction.get(fork.fork().from(d -> {
      auctionData.set(d);
      return d != null;
    }));

    user.getCreditCard(fork.fork().from(c -> {
      creditCard.set(c);
      return c != null;
    }));

    fork.join(l -> l.get(0) && l.get(1));
  }

  public void chargeUser(AuctionDataPublic auctionData,
                         CreditCard creditCard,
                         Result<Boolean> status)
  {
    _payPal.settle(auctionData,
                   _bid,
                   creditCard,
                   _userId,
                   _id,
                   status.from(x -> processPayment(x)));

  }

  private boolean processPayment(Payment payment)
  {
    _state.setPayment(payment);

    if (payment.getState().equals(Payment.PaymentState.approved)) {
      _state.setPaymentCommitState(PaymentTxState.SUCCESS);

      return true;
    }
    else {
      _state.setPaymentCommitState(PaymentTxState.FAILED);

      return false;
    }
  }

  private Status processCommit(boolean result)
  {
    Status status = Status.COMMITTING;

    if (result) {
      status = Status.COMMITTED;

      getAuction().setSettled(Result.ignore());
    }
    else if (_state.getUserCommitState() == UserUpdateState.REJECTED) {
      status = Status.COMMIT_FAILED;
    }
    else if (_state.getAuctionCommitState() == AuctionUpdateState.REJECTED) {
      status = Status.COMMIT_FAILED;
    }
    else if (_state.getPaymentCommitState() == PaymentTxState.FAILED) {
      status = Status.COMMIT_FAILED;
    }

    _state.setCommitStatus(status);

    return status;
  }

  @Modify
  @Override
  public void rollback(Result<Status> status)
  {
    if (_boundState == BoundState.UNBOUND)
      throw new IllegalStateException();

    if (_state.isRolledBack())
      throw new IllegalStateException();

    _state.toRollBack();

    rollbackImpl(status);
  }

  public void rollbackImpl(Result<Status> status)
  {
    rollbackPending(status.from(x -> processRollback(x)));
  }

  public void rollbackPending(Result<Boolean> status)
  {
    Result.Fork<Boolean,Boolean> fork = status.newFork();

    fork.fail((l, e, r) -> {
      for (Throwable t : e) {
        if (t != null) {
          r.fail(t);

          break;
        }
      }
    });

    resetUser(fork.fork());
    resetAuction(fork.fork());
    refundUser(fork.fork());

    fork.join(l -> l.get(0) && l.get(1) && l.get(2));
  }

  private Status processRollback(boolean result)
  {
    if (result) {
      getAuction().setRolledBack(Result.<Boolean>ignore());

      return Status.ROLLED_BACK;
    }
    else {
      return Status.ROLLING_BACK;
    }
  }

  private void resetUser(Result<Boolean> result)
  {
    if (_state.getUserCommitState()
        == UserUpdateState.REJECTED) {
      result.complete(true);

      return;
    }

    User user = getUser();
    user.removeWonAuction(_auctionId,
                          result.from(x -> {
                            if (x) {
                              _state.setUserCommitState(
                                UserUpdateState.ROLLED_BACK);
                              return true;
                            }
                            else {
                              throw new IllegalStateException();
                            }
                          }));
  }

  private void resetAuction(Result<Boolean> result)
  {
    if (_state.getAuctionCommitState()
        == AuctionUpdateState.REJECTED) {
      result.complete(true);

      return;
    }

    Auction auction = getAuction();

    auction.clearAuctionWinner(_userId,
                               result.from(x -> {
                                 if (x) {
                                   _state.setAuctionCommitState(
                                     AuctionUpdateState.ROLLED_BACK);
                                   return true;
                                 }
                                 else {
                                   throw new IllegalStateException();
                                 }
                               }));
  }

  private void refundUser(Result<Boolean> result)
  {
    if (_state.getPaymentCommitState() == PaymentTxState.FAILED) {
      result.complete(true);

      return;
    }

    Payment payment = _state.getPayment();
    if (payment == null) {
      //send payment to refund service
      result.complete(true);
    }
    else {
      _payPal.refund(_id, payment.getSaleId(),
                     payment.getSaleId(),
                     result.from(r -> processRefund(r)));
    }
  }

  private boolean processRefund(Refund refund)
  {
    if (refund.getStatus() == RefundImpl.RefundState.completed) {
      _state.setPaymentCommitState(PaymentTxState.REFUNDED);

      return true;
    }
    else {

      return false;
    }
  }

  @OnSave
  public void save()
  {
    //id , auction_id , user_id , bid
    if (_boundState == BoundState.NEW) {
      _db.exec(
        "insert into settlement (id, auction_id, user_id, bid) values (?, ?, ?, ?)",
        x -> _boundState = BoundState.BOUND,
        _id,
        _auctionId,
        _userId,
        _bid);
    }

    _db.exec("insert into settlement_state (id, state) values (?, ?)",
             x -> {
             },
             _id,
             _state);
  }

  @Override
  public void getTransactionState(Result<SettlementTransactionState> result)
  {
    result.complete(_state);
  }

  @Override
  public void commitStatus(Result<Status> result)
  {
    result.complete(_state.getCommitStatus());
  }

  @Override
  public void rollbackStatus(Result<Status> result)
  {
    result.complete(_state.getRollbackStatus());
  }

  @Override
  public String toString()
  {
    return this.getClass().getSimpleName()
           + "["
           + _id
           + ", "
           + _boundState
           + ", "
           + _state
           + "]";
  }

  enum BoundState
  {
    UNBOUND,
    NEW,
    BOUND
  }
}

class ValueRef<T>
{
  private T _t;

  T get()
  {
    return _t;
  }

  T set(T t)
  {
    _t = t;

    return t;
  }
}
