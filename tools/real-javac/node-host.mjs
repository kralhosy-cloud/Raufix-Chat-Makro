// Node host for the TraceJVM javac worker.
//
// TraceJVM ships an OpenJDK 23.0.2+7 javac compiled to WebAssembly (TeaVM Wasm
// GC) plus a `TraceJVMCompiler` class that drives it. Both are written for a
// browser: they call `fetch()` and `new Worker(...)`. This file supplies the
// missing host pieces — disk-backed fetch, a Worker built on node:worker_threads,
// `atob`/`btoa`, `performance` — so the very same compiler binary runs under node.
//
// Nothing here re-implements javac or the protocol it speaks.
import { Worker as NodeWorker } from "node:worker_threads";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

// ------------------------------------------------------------------ globals
if (typeof globalThis.atob !== "function") {
  globalThis.atob = (value) => Buffer.from(String(value), "base64").toString("binary");
}
if (typeof globalThis.btoa !== "function") {
  globalThis.btoa = (value) => Buffer.from(String(value), "binary").toString("base64");
}

export function installDiskFetch() {
const realFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input.url;
  // The compiler loads its SDK through a bare path and its assets through file://
  // URLs; node's fetch rejects both, so serve them straight off disk.
  if (url.startsWith("file://") || url.startsWith("/")) {
    const path = url.startsWith("file://") ? fileURLToPath(url) : url;
    try {
      const buf = await readFile(path);
      return new Response(buf, { status: 200, headers: { "content-length": String(buf.length) } });
    } catch (error) {
      return new Response(String(error), { status: 404 });
    }
  }
  return realFetch(input, init);
};
}

installDiskFetch();

// -------------------------------------------------------------- worker shim
// TraceJVM's worker bundle is an ES module that awaits messages from its host.
// node:worker_threads runs it, but speaks `parentPort` instead of `self`, so
// the messages are forwarded both ways.
class NodeWorkerShim {
  constructor(workerUrl) {
    this.worker = new NodeWorker(workerUrl, { type: "module" });
    this.listeners = { message: [], error: [] };
    this.worker.on("message", (data) => {
      const event = { data };
      for (const listener of this.listeners.message) listener(event);
      // Browser Worker objects also expose onmessage; mirror it for the bundle.
      this.onmessage?.({ data });
    });
    this.worker.on("error", (error) => {
      for (const listener of this.listeners.error) listener(error);
    });
  }

  postMessage(message) {
    this.worker.postMessage(message);
  }

  addEventListener(type, listener) {
    this.listeners[type]?.push(listener);
  }

  removeEventListener(type, listener) {
    const list = this.listeners[type];
    if (!list) return;
    const index = list.indexOf(listener);
    if (index >= 0) list.splice(index, 1);
  }

  terminate() {
    this.worker.terminate();
  }
}

export function nodeWorkerFactory(workerModule) {
  return () => new NodeWorkerShim(workerModule);
}
