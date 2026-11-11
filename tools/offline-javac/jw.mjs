// Minimal driver around the pinned OpenJDK-21 javac compiled to WasmGC
// (npm package @wasm-oj/toolchain-java 0.2.0, TeaVM 0.13.1 backend).
//
// Usage:
//   node jw.mjs compile --out out.wasm --entry src/dev/x/Main.java <src dirs or files...>
//   node jw.mjs run     --entry src/dev/x/Main.java [--stdin file] <src dirs...>
//
// It only reports what the real javac frontend reports; no re-implementation.
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { WASI } from "node:wasi";
import { compileJavaModule, defaults, hasStringBuiltins } from "./vendor/java-compiler-runtime.mjs";
import { jsBodyFactories } from "./vendor/java-compiler-bindings.mjs";

const ASSETS = process.env.JW_ASSETS;
if (!ASSETS) {
  console.error("JW_ASSETS is not set. Point it at the assets directory of the");
  console.error("npm package @wasm-oj/toolchain-java@0.2.0 - see README.md here.");
  process.exit(2);
}

function* walk(p) {
  const st = statSync(p);
  if (st.isDirectory()) {
    for (const e of readdirSync(p)) yield* walk(join(p, e));
  } else if (p.endsWith(".java")) yield p;
}

function parseArgs(argv) {
  const out = { _: [], entry: null, file: null, stdin: null, excludes: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--entry") out.entry = argv[++i];
    else if (a === "--file") out.file = argv[++i];
    else if (a === "--stdin") out.stdin = argv[++i];
    // --exclude <substring> skips any source whose path contains the
    // substring. Used to compile the platform-independent core without the
    // Minecraft client layer, which has no Minecraft or Fabric on its
    // classpath in this harness.
    else if (a === "--exclude") out.excludes.push(argv[++i]);
    else out._.push(a);
  }
  return out;
}

async function loadEngine() {
  const bytes = new Uint8Array(readFileSync(`${ASSETS}/java-teavm-0.13.1.compiler.wasm`));
  const wasmModule = await compileJavaModule(bytes);
  const imports = {};
  const exports = {};
  const context = defaults(imports, exports, { jsBodyFactories }, wasmModule, await hasStringBuiltins());
  const instance = await WebAssembly.instantiate(wasmModule, imports);
  context.supplyExports(instance.exports);
  for (const [key, value] of Object.entries(instance.exports)) {
    if (value instanceof WebAssembly.Global) Object.defineProperty(exports, key, { get: () => value.value });
  }
  if (typeof exports.createCompiler !== "function") throw new Error("no createCompiler export");
  return exports;
}

const args = parseArgs(process.argv.slice(3));
const cmd = process.argv[2];

const roots = args._;
const files = [];
// The pinned wasm javac build crashes inside com.sun.tools.javac.comp.Flow
// when it scans an annotation (reproduced with a minimal case: a class that
// overrides an interface method annotated with @Override). Blank out
// annotations of the same length so line/column numbers in diagnostics stay
// exact. This removes no semantics: @Override is compile-time only.
function blankAnnotations(text) {
  return text.replace(/@(?:Override|SuppressWarnings\s*\((?:[^()]|\([^()]*\))*\))/g,
    (m) => " ".repeat(m.length));
}

let excluded = 0;
for (const r of roots) for (const f of walk(r)) {
  if (args.excludes.some((needle) => f.includes(needle))) { excluded++; continue; }
  files.push({ path: f, content: blankAnnotations(readFileSync(f, "utf8")) });
}
if (excluded > 0) console.error(`# excluded=${excluded} (patterns: ${args.excludes.join(", ")})`);
if (cmd !== "check") {
  if (!args.entry) { console.error("missing --entry <Main.java path>"); process.exit(2); }
  if (!files.some((f) => f.path === args.entry)) { console.error(`entry ${args.entry} not in sources`); process.exit(2); }
}

const engine = await loadEngine();
const compiler = engine.createCompiler();
compiler.setSdk(new Int8Array(readFileSync(`${ASSETS}/java-teavm-0.13.1.compile-classlib.bin`)));
compiler.setTeaVMClasslib(new Int8Array(readFileSync(`${ASSETS}/java-teavm-0.13.1.runtime-classlib.bin`)));

const diagnostics = [];
const backendErrors = [];
compiler.onDiagnostic((d) => {
  if (d.type !== "javac") { if (d.severity === "error") backendErrors.push(d.message); return; }
  diagnostics.push(d);
});
for (const f of files) compiler.addSourceFile(f.path, f.content);

const ok = compiler.compile();
const errors = diagnostics.filter((d) => d.severity === "error");
for (const d of diagnostics) {
  console.log(`${d.fileName ?? "?"}:${d.lineNumber}: ${d.severity}: ${String(d.message).split("\n").join("\n  ")}`);
}
console.log(`# sources=${files.length} diagnostics=${diagnostics.length} errors=${errors.length} compileOk=${ok}`);
if (errors.length || !ok) { process.exit(1); }

if (cmd === "check") { console.log("# check ok"); process.exit(0); }

const mainClass = compiler.findEntryClass(args.entry);
if (!mainClass) { console.error("entry class not found"); process.exit(1); }
const generated = compiler.generateWebAssembly({ mainClass, outputName: "app", optimization: "debug" });
if (!generated || backendErrors.length) {
  console.error(`backend failed: ${backendErrors.join("\n")}`);
  process.exit(1);
}
const output = compiler.getWebAssemblyOutputFile("app");
if (!output) { console.error("no app.wasm"); process.exit(1); }
const wasm = Uint8Array.from(output);
console.log(`# mainClass=${mainClass} wasm=${wasm.byteLength} bytes`);
if (cmd !== "run") {
  if (args.file) writeFileSync(args.file, wasm);
  process.exit(0);
}

// Run the generated wasip1 module.
const preopens = {};
for (const spec of (process.env.JW_PREOPEN ?? "/=/").split(",")) {
  const i = spec.indexOf("=");
  if (i > 0) preopens[spec.slice(0, i)] = spec.slice(i + 1);
}
const wasi = new WASI({ version: "preview1", args: ["app"], env: {}, preopens });
const mod = await WebAssembly.compile(wasm);
const wasiImports = wasi.getImportObject();
const teavmShim = {
  // The wasm module imports teavm.nanoTime as an f64, so hand back a Number.
  nanoTime: () => Number(process.hrtime.bigint()),
  currentTimeMillis: () => Date.now(),
};
const imports = {};
const missing = new Set();
for (const imp of WebAssembly.Module.imports(mod)) {
  const holder = wasiImports[imp.module];
  if (imp.module === "teavmMath") { imports[imp.module] = Math; continue; }
  if (imp.module === "teavm" && Object.prototype.hasOwnProperty.call(teavmShim, imp.name)) {
    (imports[imp.module] ??= {})[imp.name] = teavmShim[imp.name];
    continue;
  }
  if (holder && Object.prototype.hasOwnProperty.call(holder, imp.name)) {
    (imports[imp.module] ??= {})[imp.name] = holder[imp.name];
  } else {
    missing.add(`${imp.module}.${imp.name}`);
    (imports[imp.module] ??= {})[imp.name] = () => { throw new Error(`unimplemented import ${imp.module}.${imp.name}`); };
  }
}
if (missing.size) console.log(`# unresolved imports: ${[...missing].join(", ")}`);
const inst = await WebAssembly.instantiate(mod, imports);
let code = 0;
try { code = wasi.start(inst); } catch (e) { console.error("runtime error:", e?.message ?? e); code = 70; }
process.exit(code);
