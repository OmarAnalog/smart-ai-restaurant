import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, type ConversationHistory, type ChatResponse, type Preference, type Recommendation, type User } from './api';
import './style.css';
import { Onboarding } from './Onboarding';
import { OrderRecommendations, RecommendationCard } from './OrderRecommendations';
import { OrderHistory } from './OrderHistory';

type ChatMessage = { role: 'user' | 'assistant'; content: string; recommendations?: Recommendation[]; recommendationSetId?: number };
const groups = [
  { label: 'Likes', types: ['FOOD_LIKE', 'INGREDIENT_LIKE'] },
  { label: 'Dislikes', types: ['FOOD_DISLIKE', 'INGREDIENT_DISLIKE'] },
  { label: 'Allergies', types: ['ALLERGY'] },
  { label: 'Diet', types: ['DIETARY_RESTRICTION'] },
  { label: 'Spice', types: ['SPICE_PREFERENCE'] },
  { label: 'Budget', types: ['BUDGET_MAX'] },
];
const examples = ['I love spicy food and my budget is $15.', 'I’m vegetarian and dislike mushrooms.', 'I’m allergic to peanuts. What would you suggest?'];
function App() {
  const [user, setUser] = useState<User | null>(null);
  const [username, setUsername] = useState('');
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [preferences, setPreferences] = useState<Preference[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [latestSetId, setLatestSetId] = useState<number | null>(null);
  const [orderUncertain, setOrderUncertain] = useState(false);
  const [orderRevision, setOrderRevision] = useState(0);
  const bottom = useRef<HTMLDivElement>(null);
  useEffect(() => { bottom.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages, busy]);
  async function login(event: React.FormEvent) {
    event.preventDefault(); if (busy) return;
    setBusy(true);
    setError('');
    try {
      const loggedIn = await api<User>('/users/login', { username: username.trim() });
      const remembered = await api<Preference[]>(`/users/${loggedIn.id}/preferences`);
      const history = loggedIn.onboardingCompleted
        ? await api<ConversationHistory>(`/users/${loggedIn.id}/conversations/latest`)
        : null;
      const conversation = loggedIn.onboardingCompleted && !history?.conversationId
        ? await api<{ conversationId: number }>('/conversations', { userId: loggedIn.id })
        : null;
      setPreferences(remembered);
      setConversationId(history?.conversationId ?? conversation?.conversationId ?? null);
      setUser(loggedIn);
      setMessages((history?.messages ?? []).map(message => ({
        role: message.role === 'USER' ? 'user' : 'assistant', content: message.content,
      })));
      setLatestSetId(null);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not connect to the server.'); }
    finally { setBusy(false); }
  }
  async function completeOnboarding(selections: Preference[]) {
    if (!user || busy || orderUncertain) return;
    setBusy(true);
    setError('');
    try {
      const saved = await api<Preference[]>(`/users/${user.id}/onboarding`, { preferences: selections });
      const conversation = await api<{ conversationId: number }>('/conversations', { userId: user.id });
      setPreferences(saved);
      setConversationId(conversation.conversationId);
      setUser({ ...user, onboardingCompleted: true });
      setMessages([]);
      setLatestSetId(null);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save your preferences.'); }
    finally { setBusy(false); }
  }
  async function newConversation() {
    if (!user || busy || orderUncertain) return;
    setBusy(true);
    setError('');
    try {
      const conversation = await api<{ conversationId: number }>('/conversations', { userId: user.id });
      setConversationId(conversation.conversationId);
      setMessages([]);
      setLatestSetId(null);
      setDraft('');
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not start a conversation.'); }
    finally { setBusy(false); }
  }
  async function send(text = draft) {
    if (!user || !conversationId || !text.trim() || busy || orderUncertain) return;
    setBusy(true);
    setError('');
    try {
      const response = await api<ChatResponse>('/chat', { userId: user.id, conversationId, message: text.trim() });
      setMessages(previous => [...previous, { role: 'user', content: text.trim() }, { role: 'assistant', content: response.message, recommendations: response.recommendations, recommendationSetId: response.recommendationSetId }]);
      setPreferences(response.preferences);
      setLatestSetId(response.recommendationSetId);
      setDraft('');
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not send your message.'); }
    finally { setBusy(false); }
  }
  function switchUser() {
    if (busy || orderUncertain) return;
    setLatestSetId(null);
    setUser(null);
    setConversationId(null);
    setMessages([]);
    setPreferences([]);
    setDraft('');
    setError('');
    setUsername('');
  }
  if (!user) return <main className="login-page">
    <section className="login-card">
      <div className="brand-mark" aria-hidden="true">tt.</div>
      <p className="eyebrow">GOOD FOOD. BETTER CONVERSATION.</p>
      <h1>Your next favorite<br />starts here.</h1>
      <p className="muted">Tell us what you love. We’ll find something on the menu that feels like you.</p>
      <form onSubmit={login}>
        <label htmlFor="username">What should we call you?</label>
        <input id="username" autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} placeholder="Your username" required maxLength={64} pattern={'[a-zA-Z0-9_.\\-]+'} disabled={busy || orderUncertain} />
        <button className="primary" disabled={busy || !username.trim()}>{busy ? 'Pulling up a chair…' : 'Continue →'}</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      <p className="fine-print">No password needed. Use the same username to return to your preferences. This is a shared demo, not a private account.</p>
    </section>
    <div className="login-decoration" aria-hidden="true">
      <div className="plate">
        <span>Made for<br />
          <em>your taste.</em>
        </span>
      </div>
      <p>TABLE TALK / THE MENU COMPANION</p>
    </div>
  </main>;
  if (!user.onboardingCompleted) return <Onboarding username={user.username} busy={busy} error={error} onComplete={completeOnboarding} onSwitchUser={switchUser}/>;
  return <div className="app-shell">
    <header className="topbar">
      <a className="wordmark" href="#">tt.<span>Table Talk</span>
      </a>
      <div className="account">
        <span>At the table: <strong>{user.username}</strong>
        </span>
        <button className="text-button" onClick={switchUser} disabled={busy || orderUncertain}>Switch user</button>
      </div>
    </header>
    <main className="workspace">
      <section className="chat-panel" aria-label="Restaurant conversation">
        <div className="chat-heading">
          <div>
            <p className="eyebrow">YOUR MENU COMPANION</p>
            <h1>Let’s find your flavor.</h1>
          </div>
          <button className="secondary" onClick={newConversation} disabled={busy || orderUncertain}>＋ New Conversation</button>
        </div>
        <div className="chat-scroll" aria-live="polite" aria-busy={busy}>
          {messages.length === 0 && <div className="welcome">
            <div className="small-mark">tt.</div>
            <h2>What sounds good?</h2>
            <p>Share a craving, an ingredient you avoid, or your budget.<br />I’ll help you explore our menu.</p>
            <div className="suggestions">{examples.map(example => <button key={example} onClick={() => { setDraft(example); }} disabled={busy || orderUncertain}>{example}<span>↗</span>
            </button>)}</div>{preferences.length > 0 && <p className="memory-note">A fresh conversation. Your preferences are still at the table.</p>}</div>}
          {messages.map((message, index) => <article key={index} className={`message ${message.role}`}>
            <span className="message-label">{message.role === 'user' ? 'You' : 'Table Talk'}</span>
            <p>{message.content}</p>{!!message.recommendations?.length && (message.recommendationSetId === latestSetId && conversationId ?
              <OrderRecommendations key={`${conversationId}:${latestSetId}`} items={message.recommendations} userId={user.id} conversationId={conversationId}
                setId={latestSetId} busy={busy} onBusy={setBusy} onUncertain={setOrderUncertain} onPlaced={() => setOrderRevision(n => n + 1)} />
              : <div className="recommendations">{message.recommendations.map(item => <RecommendationCard key={item.id} item={item} />)}<small>Earlier recommendations · ask again for current options.</small></div>)}</article>)}
          {busy && <p className="thinking" role="status">Finding the right fit…</p>}<div ref={bottom} />
        </div>
        <div className="composer-wrap">{error && <p className="error" role="alert">{error}</p>}<form className="composer" onSubmit={e => { e.preventDefault(); void send(); }}>
          <label className="sr-only" htmlFor="message">Your message</label>
          <textarea id="message" placeholder="What are you in the mood for?" value={draft} onChange={e => setDraft(e.target.value)} maxLength={4000} rows={2} disabled={busy || orderUncertain} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(); } }} />
          <button className="primary" disabled={busy || orderUncertain || !draft.trim()}>Send ↑</button>
        </form>
          <p className="composer-note">Real menu. Your preferences. A little help deciding.</p>
        </div>
      </section>
      <aside className="memory-panel">
        <div className="memory-heading">
          <span className="memory-icon" aria-hidden="true">✧</span>
          <h2>What I Remember</h2>
        </div>
        <p className="muted">A little about your taste, saved for next time.</p>{preferences.length === 0 ? <div className="empty-memory">
          <span aria-hidden="true">♡</span>
          <p>We’re just getting acquainted.</p>
          <small>Your preferences will appear here as we chat.</small>
        </div> : <div className="preference-groups">{groups.map(group => {
          const values = preferences.filter(p => group.types.includes(p.type)); return values.length > 0 && <section key={group.label} className={group.label === 'Allergies' ? 'allergy-group' : ''}>
            <h3>{group.label}</h3>
            <ul>{values.map(p => <li key={`${p.type}-${p.value}`}>{p.type === 'BUDGET_MAX' ? `≤ $${p.value}` : p.value}</li>)}</ul>
          </section>;
        })}</div>}<div className="memory-tip">
          <strong>Tastes change. That’s okay.</strong>
          <p>Try “Actually, mushrooms are fine now.” I’ll update what I remember.</p>
        </div>
        <OrderHistory key={`orders-${user.id}`} userId={user.id} revision={orderRevision} />
        <p className="session-label">Conversation #{conversationId}<br />Preferences belong to {user.username}.</p>
      </aside>
    </main>
  </div>;
}
createRoot(document.getElementById('root')!).render(<React.StrictMode>
  <App />
</React.StrictMode>);
