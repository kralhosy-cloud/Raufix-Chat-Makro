// Entry point for the javac worker under node:worker_threads.
//
// TraceJVM's worker bundle expects the browser worker globals (`self`, `scope`,
// `postMessage`). node:worker_threads has `parentPort` instead, so this shim maps
// one onto the other and then imports the untouched bundle.
import { parentPort } from "node:worker_threads";
import { installDiskFetch } from "./node-host.mjs";

// The compiler runs here, in the worker, so the disk-backed fetch must be
// installed in this realm (a fetch hook in the host thread would never be used).
installDiskFetch();

const listeners = new Set();
const self = globalThis;
globalThis.self = self;

self.addEventListener = (type, listener) => {
  if (type === "message") listeners.add(listener);
};
self.removeEventListener = (type, listener) => {
  if (type === "message") listeners.delete(listener);
};
globalThis.postMessage = (message) => parentPort.postMessage(message);

parentPort.on("message", (data) => {
  const event = { data };
  for (const listener of listeners) listener(event);
  self.onmessage?.(event);
});

self.onmessage = null;
await import("./tracejvm/dist/browser-worker.js");
