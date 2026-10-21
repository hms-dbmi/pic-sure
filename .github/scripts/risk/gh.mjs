// Minimal GitHub REST client on global fetch. No npm dependencies: risk-classifier.yml runs this without npm ci.
import { appendFileSync } from 'node:fs';

export function client(token, repo) {
  const base = process.env.GITHUB_API_URL || 'https://api.github.com';
  const headers = {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'risk-classifier',
  };

  async function request(method, path, body) {
    const res = await fetch(path.startsWith('http') ? path : `${base}${path}`, {
      method,
      headers: body ? { ...headers, 'Content-Type': 'application/json' } : headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let data;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = text;
    }
    return { status: res.status, ok: res.ok, data, headers: res.headers };
  }

  async function must(method, path, body) {
    const r = await request(method, path, body);
    if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${JSON.stringify(r.data).slice(0, 300)}`);
    return r.data;
  }

  // Follows Link: rel="next" and concatenates the arrays.
  async function paginate(path) {
    const out = [];
    let url = `${base}${path}${path.includes('?') ? '&' : '?'}per_page=100`;
    for (let i = 0; i < 50 && url; i++) {
      const r = await request('GET', url);
      if (!r.ok) throw new Error(`GET ${url} -> ${r.status} ${JSON.stringify(r.data).slice(0, 300)}`);
      out.push(...r.data);
      const next = /<([^>]+)>;\s*rel="next"/.exec(r.headers.get('link') || '');
      url = next ? next[1] : null;
    }
    return out;
  }

  return { repo, request, must, paginate };
}

export function summary(markdown) {
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown + '\n');
}

export function requireEnv(...names) {
  const missing = names.filter((n) => !process.env[n]);
  if (missing.length) throw new Error(`Missing environment variable(s): ${missing.join(', ')}`);
  return names.map((n) => process.env[n]);
}
