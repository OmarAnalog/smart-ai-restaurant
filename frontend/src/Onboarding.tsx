import { useState, type FormEvent } from 'react';
import type { Preference } from './api';

type Props = {
  username: string;
  busy: boolean;
  error: string;
  onComplete: (preferences: Preference[]) => Promise<void>;
  onSwitchUser: () => void;
};
const allergens = ['peanut', 'milk', 'wheat', 'egg', 'soy', 'sesame', 'fish', 'shellfish', 'nut'];
const allergenLabels: Record<string, string> = { nut: 'Tree nuts', milk: 'Milk / dairy', wheat: 'Wheat / gluten' };

export function Onboarding({ username, busy, error, onComplete, onSwitchUser }: Props) {
  const [likes, setLikes] = useState('');
  const [dislikes, setDislikes] = useState('');
  const [allergies, setAllergies] = useState<string[]>([]);
  const [diet, setDiet] = useState('');
  const [spice, setSpice] = useState('');
  const [budget, setBudget] = useState('');

  function submit(event: FormEvent) {
    event.preventDefault();
    const preferences: Preference[] = [];
    function addIngredients(type: string, input: string) {
      [...new Set(input.split(',').map(value => value.trim().toLowerCase()).filter(Boolean))]
        .forEach(value => preferences.push({ type, value }));
    }
    addIngredients('INGREDIENT_LIKE', likes);
    addIngredients('INGREDIENT_DISLIKE', dislikes);
    allergies.forEach(value => preferences.push({ type: 'ALLERGY', value }));
    if (diet) preferences.push({ type: 'DIETARY_RESTRICTION', value: diet });
    if (spice) preferences.push({ type: 'SPICE_PREFERENCE', value: spice });
    if (budget) preferences.push({ type: 'BUDGET_MAX', value: budget });
    void onComplete(preferences);
  }

  return <main className="onboarding-page">
    <section className="onboarding-card" aria-labelledby="onboarding-title">
      <div className="onboarding-top"><span className="wordmark">tt.</span><button className="text-button" onClick={onSwitchUser} disabled={busy}>Switch user</button></div>
      <p className="eyebrow">LET’S GET TO KNOW YOUR TASTE</p>
      <h1 id="onboarding-title">Welcome to the table, {username}.</h1>
      <p className="muted">A few preferences help us find your first favorites. Everything is optional, and you can change your mind in chat anytime.</p>
      <form onSubmit={submit}>
        <fieldset disabled={busy}>
          <legend className="sr-only">Your food preferences</legend>
          <div className="onboarding-grid">
            <label>Ingredients you love<input value={likes} onChange={e => setLikes(e.target.value)} placeholder="Chicken, avocado, tomato" maxLength={300}/><small>Separate ingredients with commas.</small></label>
            <label>Ingredients you avoid<input value={dislikes} onChange={e => setDislikes(e.target.value)} placeholder="Mushroom, onion" maxLength={300}/><small>Dislikes only. Add allergies below.</small></label>
          </div>
          <fieldset className="allergy-options">
            <legend>Any food allergies?</legend>
            <p className="field-hint">Select any that apply. Tell us about other allergies in chat before asking for recommendations.</p>
            <div className="allergy-chips">{allergens.map(allergen => <label key={allergen}>
              <input type="checkbox" checked={allergies.includes(allergen)} onChange={e => setAllergies(previous => e.target.checked ? [...previous, allergen] : previous.filter(value => value !== allergen))}/>
              <span>{allergenLabels[allergen] || allergen}</span>
            </label>)}</div>
          </fieldset>
          <div className="onboarding-grid">
            <label>Diet<select value={diet} onChange={e => setDiet(e.target.value)}><option value="">No preference</option><option value="vegetarian">Vegetarian</option><option value="vegan">Vegan</option><option value="gluten-free">Gluten-free</option><option value="dairy-free">Dairy-free</option></select></label>
            <label>How spicy?<select value={spice} onChange={e => setSpice(e.target.value)}><option value="">No preference</option><option value="mild">Mild</option><option value="medium">Medium</option><option value="spicy">Spicy</option></select></label>
            <label>Maximum meal budget ($)<input type="number" min="0.01" max="100000" step="0.01" value={budget} onChange={e => setBudget(e.target.value)} placeholder="e.g. 15"/><small>Leave blank for no budget limit.</small></label>
          </div>
        </fieldset>
        {error && <p className="error" role="alert">{error}</p>}
        <div className="onboarding-actions"><button type="button" className="text-button" disabled={busy} onClick={() => void onComplete([])}>Skip for now</button><button className="primary" disabled={busy}>{busy ? 'Saving your seat…' : 'Save & start chatting →'}</button></div>
      </form>
    </section>
  </main>;
}
