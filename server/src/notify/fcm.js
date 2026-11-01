// Firebase Cloud Messaging topic data messages (HTTP v1). The service account comes
// from GOOGLE_APPLICATION_CREDENTIALS.
import fs from 'node:fs';
import { GoogleAuth } from 'google-auth-library';
import { config } from '../config.js';

let auth = null;

export const enabled = () => {
  const f = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  return !!f && fs.existsSync(f);
};

export async function send(msg) {
  auth ||= new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/firebase.messaging'] });
  const projectId = await auth.getProjectId();
  const client = await auth.getClient();
  // FCM data values must all be strings.
  const data = Object.fromEntries(Object.entries({ type: msg.type, title: msg.title, body: msg.body, ...msg.data })
    .filter(([, v]) => v !== undefined && v !== null).map(([k, v]) => [k, String(v)]));
  await client.request({
    url: `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
    method: 'POST',
    timeout: 15_000,
    data: {
      message: {
        topic: config.fcmTopic,
        // A stale in-stock alert an hour later is noise.
        android: { priority: msg.type === 'in_stock' || msg.type === 'test' ? 'high' : 'normal', ttl: '300s' },
        data,
      },
    },
  });
}
