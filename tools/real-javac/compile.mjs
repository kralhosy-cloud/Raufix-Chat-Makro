// Compiles Java sources to real .class files with the TraceJVM javac worker.
//
//   node tools/real-javac/compile.mjs --out build/classes \
//        --platform tools/real-javac/runtime/jdk23.jar \
//        [--exclude /client/] <source dir|file>...
//
// The compiler is OpenJDK 23.0.2+7 javac compiled to WebAssembly. It really does
// emit JVM class files (constant pool, method code, stack maps); the output of
// this script runs on a stock JVM - that is checked by scripts/build-jar.sh.
//
// Vendor layout (see tools/real-javac/README.md):
//   tracejvm/dist/                 @tracecode/tracejvm 0.4.1 (AGPL-3.0-or-later)
//   tracejvm/node_modules/         its two runtime dependencies
//   compiler/                      TeaVM-javac wasm artifacts (the compiler itself)
//   runtime/jdk23.jar              JDK 23 image used as the compile platform
import { writeFileSync, mkdirSync, readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const { TraceJVMCompilerWorkerClient } = await import(join(HERE, "tracejvm/dist/index.js"));

function usage(message) {
  if (message) console.error(message);
  console.error("usage: compile.mjs --out DIR --platform JDK.jar [--exclude TEXT] <sources...>");
  process.exit(2);
}

const args = { _: [], out: null, testOut: null, testRoots: [], platform: null, excludes: [], quiet: false };
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const arg = argv[i];
  if (arg === "--out") args.out = argv[++i];
  else if (arg === "--platform") args.platform = argv[++i];
  else if (arg === "--exclude") args.excludes.push(argv[++i]);

  else if (arg === "--quiet") args.quiet = true;
  else if (arg === "--test-classes") args.testOut = argv[++i];
  else if (arg === "--test-root") args.testRoots.push(argv[++i]);
  else if (arg.startsWith("--")) usage(`unknown flag ${arg}`);
  else args._.push(arg);
}
if (!args.out || !args.platform || args._.length === 0) usage();

const compilerDir = join(HERE, "compiler");
for (const required of ["compiler.wasm", "compiler.wasm-runtime.js", "compile-classlib-teavm.bin"]) {
  if (!existsSync(join(compilerDir, required))) {
    console.error(`missing compiler asset ${required}; see tools/real-javac/README.md`);
    process.exit(2);
  }
}

function* walk(target) {
  const stat = statSync(target);
  if (stat.isDirectory()) {
    for (const entry of readdirSync(target)) yield* walk(join(target, entry));
  } else if (target.endsWith(".java") || target.endsWith(".class")) {
    yield target;
  }
}

const files = [];
let excluded = 0;
for (const root of args._) {
  for (const file of walk(root)) {
    if (args.excludes.some((needle) => file.includes(needle))) {
      excluded++;
      continue;
    }
    files.push(file);
  }
}
if (files.length === 0) usage("no Java sources found");

// The compiler validates every path, so hand it a stable relative one.
const sourceIndex = new Map();   // "src<index>/" prefix -> is it a test source?
const sources = files.map((file, index) => ({
  path: `src${index}/${file.replace(/^\/+/, "").replace(/[:\\]/g, "_")}`,
  content: readFileSync(file, "utf8"),
}));
files.forEach((file, index) => {
  sourceIndex.set(`src${index}/`, args.testRoots.some((root) => file.startsWith(resolve(root))));
});

// Let TraceJVM serve its own assets from disk.
const { nodeWorkerFactory } = await import(join(HERE, "node-host.mjs"));
const client = new TraceJVMCompilerWorkerClient({
  compiler: {
    assets: { baseUrl: compilerDir },
    platformArchiveUrl: resolve(args.platform),
    platformClasspath: [],
  },
  createWorker: () => nodeWorkerFactory(join(HERE, "worker-boot.mjs"))(),
});

const startedAt = Date.now();
await client.initialize();
const result = await client.compile({ sources });
const elapsed = Date.now() - startedAt;

if (result.diagnostics.length > 0) {
  for (const diagnostic of result.diagnostics) {
    console.log(`${diagnostic.fileName ?? "?"}:${diagnostic.lineNumber ?? 0}: ${diagnostic.severity}: ${diagnostic.message}`);
  }
}
const errors = result.diagnostics.filter((d) => d.severity === "error");
if (!args.quiet) {
  const note = excluded > 0 ? ` excluded=${excluded}` : "";
  console.log(`# sources=${files.length}${note} errors=${errors.length} status=${result.status} ms=${elapsed}`);
}

// javac reports class files by their package path, not by the source root they
// came from, so the split is done afterwards: every class whose name matches a
// test source is moved to --test-classes. Top-level and nested (Foo$Bar) classes
// of the same source move together.
const testStems = new Set();
for (const root of args.testRoots) {
  for (const file of walk(resolve(root))) {
    if (!file.endsWith(".java")) continue;
    testStems.add(relative(resolve(root), file).replace(/\.java$/, "").replace(/\\/g, "/"));
  }
}

let written = 0;
let movedToTests = 0;
if (result.program) {
  for (const output of result.program.files) {
    const isTest = args.testRoots.length > 0 &&
      [...testStems].some((stem) => output.path === `${stem}.class` || output.path.startsWith(`${stem}$`));
    const root = isTest && args.testOut ? args.testOut : args.out;
    if (isTest) movedToTests++;
    const target = join(root, output.path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, Buffer.from(output.content));
    written++;
  }
}
await client.dispose();
if (!args.quiet) console.log(`# classfiles=${written} tests=${movedToTests} out=${args.out}`);
process.exit(errors.length > 0 || !result.program ? 1 : 0);
