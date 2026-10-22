import { useEffect, useState } from 'react';
import { api, type Order } from './api';
export function OrderHistory({ userId, revision }: { userId: number; revision: number }) {
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let cancelled = false;
    void api<Order[]>(`/users/${userId}/orders`).then(result => { if (!cancelled) { setOrders(result); setError(''); } })
      .catch(() => { if (!cancelled) setError('Could not load orders.'); });
    return () => { cancelled = true; };
  }, [userId, revision, refresh]);
  return <section className="order-history"><h3>Your orders</h3>
    <button className="text-button" onClick={() => setRefresh(n => n + 1)}>Refresh orders</button>
    {error && <p role="alert">{error}</p>}
    {!orders.length && !error && <p>No orders yet.</p>}
    {orders.map(order => <details key={order.id}><summary>#{order.id} · ${order.total.toFixed(2)}</summary>
      <small>{new Date(order.createdAt).toLocaleString()}</small>
      <ul>{order.items.map(i => <li key={i.menuItemId}>{i.quantity} × {i.name} · ${i.lineTotal.toFixed(2)}</li>)}</ul>
    </details>)}
  </section>;
}
