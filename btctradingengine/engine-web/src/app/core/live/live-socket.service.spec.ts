import { reconnectDelay } from './live-socket.service';

describe('reconnectDelay', () => {
  it('backs off exponentially up to 15 seconds', () => {
    expect([1, 2, 3, 4, 5, 6].map(reconnectDelay)).toEqual([1000, 2000, 4000, 8000, 15000, 15000]);
  });
});
