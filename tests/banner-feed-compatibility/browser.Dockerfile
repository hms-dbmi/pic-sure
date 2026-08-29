FROM mcr.microsoft.com/playwright:v1.60.0-noble@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948
WORKDIR /proof
COPY browser-package.json package.json
COPY browser-package-lock.json package-lock.json
RUN PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
    && npm cache clean --force
COPY browser.mjs browser.mjs
ENTRYPOINT ["node", "/proof/browser.mjs"]
