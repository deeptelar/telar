// The worker of SQLDelight loads the SQLite of sql.js from /sql-wasm.wasm. An app copies the file
// there; the tests serve it from node_modules.
const sqlWasm = require.resolve("sql.js/dist/sql-wasm.wasm");
config.files.push({ pattern: sqlWasm, included: false, served: true, watched: false });
config.proxies = config.proxies || {};
config.proxies["/sql-wasm.wasm"] = "/absolute" + sqlWasm;
