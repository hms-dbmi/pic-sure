import fs from 'node:fs';
import { chromium } from 'playwright';

const [configPath, resultPath] = process.argv.slice(2);
if (!configPath || !resultPath) {
  throw new Error('usage: browser.mjs <config.json> <result.json>');
}

const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: false });
const page = await context.newPage();
const requestedFeedUrls = [];
const responseCaptures = [];

async function bounded(promise, milliseconds, label) {
  let timeout;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error(`${label} timed out after ${milliseconds} ms`)), milliseconds);
      }),
    ]);
  } finally {
    clearTimeout(timeout);
  }
}

page.on('request', (request) => {
  const url = new URL(request.url());
  if (url.pathname.includes('/operations/banners/active')) requestedFeedUrls.push(url.pathname);
});
page.on('response', (response) => {
  const url = new URL(response.url());
  if (!url.pathname.includes('/operations/banners/active')) return;
  if (!response.ok()) {
    responseCaptures.push(
      Promise.resolve({ url: url.pathname, status: response.status(), body: null }),
    );
    return;
  }
  responseCaptures.push(
    response
      .text()
      .then((body) => ({ url: url.pathname, status: response.status(), body }))
      .catch((error) => ({ url: url.pathname, status: response.status(), bodyError: String(error) })),
  );
});

let failure;
try {
  const failedFeedLogged =
    config.expectedStatus >= 400
      ? page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/log', {
          timeout: 30000,
        })
      : null;
  const awaitedFeed = page.waitForResponse(
    (response) => new URL(response.url()).pathname === config.expectedFeedPath,
    { timeout: 30000 },
  );
  await page.goto(`http://frontend${config.browserPath}`, {
    waitUntil: 'domcontentloaded',
    timeout: 30000,
  });
  const response = await awaitedFeed;
  console.log(`observed ${new URL(response.url()).pathname} HTTP ${response.status()}`);
  await page.getByTestId('login-title').waitFor({ state: 'visible', timeout: 30000 });
  if (failedFeedLogged) await failedFeedLogged;
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );

  const region = page.getByTestId('site-banner-region');
  const regionPresent = (await region.count()) === 1 && (await region.isVisible());
  const regionText = regionPresent ? await region.innerText() : '';
  const renderedMarkers = config.markerUniverse.filter((marker) => regionText.includes(marker));
  const feedResponses = await bounded(Promise.all(responseCaptures), 10000, 'feed response capture');
  const result = {
    browserPath: config.browserPath,
    requestedFeedUrls,
    feedResponses,
    renderedMarkers,
    regionPresent,
    retriesDisabled: true,
  };
  fs.writeFileSync(resultPath, `${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  failure = error;
  const diagnostic = {
    error: error instanceof Error ? error.stack : String(error),
    requestedFeedUrls,
    feedResponses: await bounded(Promise.all(responseCaptures), 10000, 'failure response capture').catch(
      (captureError) => [{ bodyError: String(captureError) }],
    ),
    html: await page.content().catch(() => ''),
    retriesDisabled: true,
  };
  fs.writeFileSync(resultPath, `${JSON.stringify(diagnostic, null, 2)}\n`);
} finally {
  await bounded(context.close(), 10000, 'browser context close').catch(() => undefined);
  await bounded(browser.close(), 10000, 'browser close').catch(() => undefined);
}
if (failure) throw failure;
