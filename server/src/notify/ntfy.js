// Publishes to an ntfy topic (ntfy.sh or self-hosted). JSON publishing, so titles
// can hold non-ASCII text that HTTP headers can't.
import { config } from '../config.js';

const PRIORITY = { in_stock: 5, test: 5, blocked: 3, recovered: 3, heartbeat: 2 };
const TAGS = { in_stock: ['rotating_light'], test: ['test_tube'], blocked: ['warning'], recovered: ['white_check_mark'], heartbeat: ['green_heart'] };

export const enabled = () => !!config.ntfyTopic;

export async function send(msg) {
  const body = {
    topic: config.ntfyTopic,
    title: msg.title,
    message: msg.body,
    priority: PRIORITY[msg.type] || 3,
    tags: TAGS[msg.type] || [],
  };
  if (msg.url) body.click = msg.url;
  const headers = { 'Content-Type': 'application/json' };
  if (config.ntfyToken) headers.Authorization = `Bearer ${config.ntfyToken}`;
  const res = await fetch(config.ntfyUrl, {
    method: 'POST', headers, body: JSON.stringify(body), signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) throw new Error(`ntfy HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
}
