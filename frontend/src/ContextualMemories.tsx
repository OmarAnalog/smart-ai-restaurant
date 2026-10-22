import { useEffect, useState } from 'react';
import { api } from './api';

type Memory = { id: number; content: string; status: string; attempts: number; errorCategory: string | null };
const labels: Record<string, string> = { PENDING: 'Queued', PROCESSING: 'Indexing', ACTIVE: 'Ready', FAILED: 'Indexing failed' };
export function ContextualMemories({ userId, revision }: { userId: number; revision: number }) {
  const [memories, setMemories] = useState<Memory[]>([]);
  const [unavailable, setUnavailable] = useState(false);
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const result = await api<Memory[]>(`/users/${userId}/memories`);
        if (alive) { setMemories(result); setUnavailable(false); }
      } catch { if (alive) setUnavailable(true); }
      finally { if (alive) timer = setTimeout(() => { void refresh(); }, 4000); }
    }
    void refresh();
    return () => { alive = false; clearTimeout(timer); };
  }, [userId, revision]);
  return <section className="contextual-memories" aria-label="Contextual memories">
    <h3>Conversation memories</h3>
    <p className="muted">Past food requests, saved as context. Your preferences above take priority.</p>
    {unavailable ? <small>Memory status is temporarily unavailable.</small> : memories.length === 0 ? <small>No conversation memories yet.</small> :
      <ul>{memories.map(memory => <li key={memory.id}>
        <p>{memory.content}</p><small>{labels[memory.status] ?? memory.status}{memory.status === 'FAILED' ? ' · your preferences are still saved' : ''}</small>
      </li>)}</ul>}
  </section>;
}
