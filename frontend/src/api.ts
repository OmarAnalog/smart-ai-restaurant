export type User = { id: number; username: string; onboardingCompleted: boolean };
export type Preference = { type: string; value: string };
export type Recommendation = { id: string; name: string; price: number; spiceLevel: number; ingredients: string[]; reason: string };
export type ChatResponse = { message: string; preferences: Preference[]; recommendations: Recommendation[]; recommendationSetId: number };
export type Order = { id: number; createdAt: string; total: number; items: { menuItemId: string; name: string; unitPrice: number; quantity: number; lineTotal: number }[] };
export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string) { super(message); }
}
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 110_000);
  try {
    const response = await fetch(`/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new ApiError(result.detail || `Request failed (${response.status}). Please try again.`, response.status, result.code);
    return result as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error(path === '/orders' ? 'Order confirmation timed out. Retry the same order request safely.' : 'The request timed out. Please start a new conversation before retrying if its result is uncertain.');
    throw error;
  } finally { window.clearTimeout(timeout); }
}

export type ConversationHistory = {
  conversationId: number | null;
  messages: { id: number; role: 'USER' | 'ASSISTANT'; content: string; createdAt: string }[];
};
