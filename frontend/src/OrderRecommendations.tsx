import { useState } from 'react';
import { api, ApiError, type Order, type Recommendation } from './api';

export function RecommendationCard({ item, children }: { item: Recommendation; children?: React.ReactNode }) {
  return <section className="recommendation-card">
    <div className="card-top"><h3>{item.name}</h3><span>${item.price.toFixed(2)}</span></div>
    <p className="spice">Spice: {item.spiceLevel}/5</p>
    <p className="ingredients">{item.ingredients.join(', ')}</p>
    <div className="card-reason"><strong>Why this fits</strong><p>{item.reason}</p></div>
    {children}
  </section>;
}

export function OrderRecommendations({ items, userId, conversationId, setId, busy, onBusy, onUncertain, onPlaced }: {
  items: Recommendation[]; userId: number; conversationId: number; setId: number; busy: boolean;
  onBusy: (busy: boolean) => void; onUncertain: (uncertain: boolean) => void; onPlaced: () => void;
}) {
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [attempt, setAttempt] = useState<{ requestId: string; items: { menuItemId: string; quantity: number }[] } | null>(null);
  const [error, setError] = useState('');
  const [placed, setPlaced] = useState<Order | null>(null);
  const selected = items.filter(i => (quantities[i.id] ?? 0) > 0);
  const total = selected.reduce((sum, i) => sum + i.price * quantities[i.id], 0);
  async function place() {
    if (busy) return;
    const submission = attempt ?? { requestId: crypto.randomUUID(), items: selected.map(i => ({ menuItemId: i.id, quantity: quantities[i.id] })) };
    if (!submission.items.length) return;
    setAttempt(submission); setError(''); onBusy(true);
    try {
      const result = await api<Order>('/orders', { userId, conversationId, recommendationSetId: setId, ...submission });
      setPlaced(result); setAttempt(null); setQuantities({}); onUncertain(false); onPlaced();
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) {
        setAttempt(null); onUncertain(false);
        setError(e.message);
      } else {
        // Keep the exact payload and UUID: the server may already have committed.
        onUncertain(true);
        setError('The order result is uncertain. Retry confirmation below to check the same request safely; it will not create a duplicate.');
      }
    } finally { onBusy(false); }
  }
  return <>
    <div className="recommendations">{items.map(item => <RecommendationCard key={item.id} item={item}>
      <label className="quantity-control">Quantity for {item.name}
        <input type="number" min={0} max={99} step={1} value={quantities[item.id] ?? 0}
          disabled={busy || !!attempt || !!placed}
          onChange={e => { setError(''); setQuantities(q => ({ ...q, [item.id]: Math.max(0, Math.min(99, Math.trunc(Number(e.target.value) || 0))) })); }} />
      </label>
    </RecommendationCard>)}</div>
    {placed ? <div className="order-confirmation" role="status"><strong>Order #{placed.id} placed · ${placed.total.toFixed(2)}</strong>
      <p>{placed.items.map(i => `${i.quantity} × ${i.name}`).join(' · ')}</p>
      <p>Ask for new recommendations when you’re ready to order again.</p></div>
      : <div className="order-summary">
        <strong>Your selection · ${total.toFixed(2)}</strong>
        <p>{selected.length ? selected.map(i => `${quantities[i.id]} × ${i.name}`).join(' · ') : 'Choose quantities from these recommendations.'}</p>
        <small>Availability and saved preferences are checked when you confirm. Your budget applies per item.</small>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy || (!selected.length && !attempt)} onClick={() => void place()}>
          {busy ? 'Confirming…' : attempt ? 'Retry confirmation safely' : 'Place order'}
        </button>
      </div>}
  </>;
}
