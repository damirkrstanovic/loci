import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, launchBrowser, withPage, bootedShell } from './harness.mjs';

let server, browser;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });

test('boots and renders the seeded world', async () => {
  await withPage(browser, 'boots-and-renders', async (page) => {
    await bootedShell(page, server.url);
    const api = await (await fetch(`${server.url}/api/state`)).json();
    const strip = await page.textContent('#ambient');
    assert.match(strip, new RegExp(`${api.spaces.length} notebooks`), `topbar: ${strip}`);
    assert.match(strip, new RegExp(`${api.events} events`), `topbar: ${strip}`);
    assert.equal(await page.locator('.panel').count(), api.spaces.length);
  });
});
