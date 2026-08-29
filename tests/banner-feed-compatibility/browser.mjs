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
  const failureSynchronization =
    config.failureSynchronizationPath
      ? page.waitForResponse((response) => new URL(response.url()).pathname === config.failureSynchronizationPath, {
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
  const observedFeedPath = new URL(response.url()).pathname;
  const observedFeedStatus = response.status();
  console.log(`observed ${observedFeedPath} HTTP ${observedFeedStatus}`);
  await page.getByTestId('login-title').waitFor({ state: 'visible', timeout: 30000 });
  const failureSynchronizationResponse = failureSynchronization ? await failureSynchronization : null;
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );

  const region = page.getByTestId('site-banner-region');
  const regionPresent = (await region.count()) === 1 && (await region.isVisible());
  const bannerTexts = regionPresent
    ? await region.getByTestId('site-banner').allTextContents()
    : [];
  const renderedMarkers = bannerTexts.map((text) => {
    const matches = config.markerUniverse.filter((marker) => text.includes(marker));
    if (matches.length !== 1) throw new Error(`banner DOM marker ambiguity: ${JSON.stringify({ text, matches })}`);
    return matches[0];
  });
  const feedResponses = await bounded(Promise.all(responseCaptures), 10000, 'feed response capture');
  const result = {
    browserPath: config.browserPath,
    pageUrl: page.url(),
    requestedFeedUrls,
    feedResponses,
    observedFeedPath,
    observedFeedStatus,
    renderedMarkers,
    regionPresent,
    failureSynchronization: failureSynchronizationResponse
      ? {
          path: new URL(failureSynchronizationResponse.url()).pathname,
          status: failureSynchronizationResponse.status(),
        }
      : null,
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
