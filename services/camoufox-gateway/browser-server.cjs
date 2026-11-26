// Python does not expose BrowserType.launchServer. Use its pinned, bundled Node
// driver and public Firefox launchServer API; never invent a Browser.ws_endpoint.
const fs = require('node:fs');
const path = require('node:path');
const playwright = require(path.join(process.argv[2], 'index.js'));
let server;
let closing = false;
let parentGone = false;

async function stop() {
  parentGone = true;
  if (!server || closing) return;
  closing = true;
  const force = setTimeout(() => server.kill().finally(() => process.exit(1)), 5000);
  try {
    await server.close();
    clearTimeout(force);
    process.exit(0);
  } catch (_) {
    await server.kill().catch(() => {});
    process.exit(1);
  }
}

// Parent pipe EOF also handles a crashed gateway. Signals use the same teardown.
process.stdin.on('end', stop);
process.stdin.on('error', stop);
process.stdin.resume();
process.on('SIGTERM', stop);
process.on('SIGINT', stop);

(async () => {
  const file = process.argv[3];
  const options = JSON.parse(fs.readFileSync(file, 'utf8'));
  fs.unlinkSync(file);
  server = await playwright.firefox.launchServer(options);
  if (parentGone) return stop();
  // This line is private IPC, consumed by Python and never copied into access logs.
  console.log('GATEWAY_READY ' + JSON.stringify({
    wsEndpoint: server.wsEndpoint(), browserPid: server.process().pid
  }));
  // The close event precedes Playwright's temporary-profile cleanup. Do not call
  // process.exit() here; let pending cleanup I/O and server handles drain.
  server.on('close', () => process.stdin.destroy());
})().catch(() => {
  console.error('GATEWAY_START_FAILED');
  process.exit(1);
});
