package examples.auction;

public class MockRefund implements Refund
{
  private RefundState _state;

  public MockRefund()
  {
  }

  public MockRefund(RefundState state)
  {
    _state = state;
  }

  @Override
  public RefundState getStatus()
  {
    return _state;
  }
}
