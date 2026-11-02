// DSH-Android overlay plugin: device-local replacements modelled on
// kylin-agent's tool set (/usr-data/data2/kylin/kylin-agent KylinTools.kt).
//
// 1. bash: dsh-tool-bash needs a Landlock/bubblewrap sandbox backend Android
//    has not. The preset rows point the tool-bash slot at this package, which
//    runs /system/bin/sh (mksh) directly — kylin-agent's `shell` semantics.
// 2. web search: dsh-web-search-deepseek is hardwired to the DeepSeek search
//    API (no API key on this device). This plugin registers a DuckDuckGo
//    search provider ("kylin-ddg") on the web service; the stock web_search
//    tool keeps its name and UX, only the backend changes (selected via
//    DSH_WEB_SEARCH_PROVIDER=kylin-ddg). Device egress goes through the host
//    proxy tunnel (HTTP(S)_PROXY env, honored by fetch).
//
// Loaded both top-level (registers the provider) and inside agent presets
// (registers the bash tool); each registration is guarded so the other
// composition skips it.
import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

// This package is loaded from the profile's node_modules, whose ancestors
// never pass through the runtime tree's web/node_modules — probe both the
// sibling layout (next to @deepseek-ai/*) and the "web/"-rooted one while
// walking up.
let defineTool;
{
	const suffix = "node_modules/@deepseek-ai/dsh-tools/lib/index.js";
	let dir = dirname(fileURLToPath(import.meta.url));
	for (let i = 0; i < 12 && defineTool === undefined; i += 1) {
		for (const candidate of [resolve(dir, suffix), resolve(dir, "web", suffix)]) {
			try {
				const imported = (await import(pathToFileURL(candidate).href)).defineTool;
				if (imported !== undefined) {
					defineTool = imported;
					break;
				}
			} catch {
				/* try the next candidate */
			}
		}
		dir = dirname(dir);
	}
	if (defineTool === undefined) throw new Error("kylin-tools: cannot locate the dsh runtime module tree");
}

const name = "kylin-tools";
const inject = [];

// ---------------------------------------------------------------- bash tool

const MAX_OUTPUT = 120_000;
const SHELL = "/system/bin/sh";

function runShell(command, timeoutMs, signal) {
	return new Promise((resolve) => {
		const child = spawn(SHELL, ["-c", command], {
			cwd: process.cwd(),
			env: process.env,
			stdio: ["ignore", "pipe", "pipe"],
		});
		let out = "";
		let why = "";
		const collect = (chunk) => {
			if (out.length < MAX_OUTPUT * 2) out += chunk;
		};
		child.stdout.setEncoding("utf8").on("data", collect);
		child.stderr.setEncoding("utf8").on("data", collect);
		const timer = setTimeout(() => {
			why = "timeout";
			child.kill("SIGKILL");
		}, timeoutMs);
		const abort = () => {
			why = "aborted";
			child.kill("SIGKILL");
		};
		signal?.addEventListener("abort", abort, { once: true });
		child.on("error", (error) => {
			clearTimeout(timer);
			signal?.removeEventListener("abort", abort);
			resolve({ exitCode: 127, timedOut: false, output: `spawn ${SHELL} failed: ${error.message}` });
		});
		child.on("close", (code) => {
			clearTimeout(timer);
			signal?.removeEventListener("abort", abort);
			const truncated = out.length > MAX_OUTPUT;
			const text = out.slice(0, MAX_OUTPUT) + (truncated ? `\n...[truncated ${out.length - MAX_OUTPUT} chars]` : "");
			resolve({ exitCode: code ?? 1, timedOut: why === "timeout", output: text === "" && why === "" ? "(no output)" : text });
		});
	});
}

const bashTool = defineTool({
	name: "bash",
	description:
		`Run a command in the Android shell (${SHELL}, mksh) and return exit code with combined stdout/stderr. ` +
		"System inspection (dumpsys/getprop/settings/am/pm/ps/df/cat/ls) and workspace file work run directly. " +
		"Multi-step tasks: issue one command per call, read the output, then decide the next. " +
		"Some desktop ROMs restrict a few settings writes; the tool reports refusals verbatim.",
	parameters: {
		command: { type: "string", description: "The shell command to execute." },
		description: { type: "string", description: "Short summary of what this command does." },
		timeoutMs: { type: "number", description: "Kill the command after this many milliseconds (default 60000)." },
	},
	output: {
		schema: {
			type: "object",
			additionalProperties: false,
			properties: {
				exitCode: { type: "number", required: true, description: "Process exit code." },
				timedOut: { type: "boolean", required: true, description: "Whether the timeout killed the command." },
				output: { type: "string", required: true, description: "Combined stdout+stderr, possibly truncated." },
			},
		},
		render: (_args, value) => [{ type: "text", text: `exit=${value.exitCode}${value.timedOut ? " (timeout)" : ""}\n${value.output}` }],
	},
	async execute(args, exec) {
		const timeoutMs = Number.isFinite(args.timeoutMs) && args.timeoutMs > 0 ? Math.min(args.timeoutMs, 600_000) : 60_000;
		return runShell(String(args.command ?? ""), timeoutMs, exec.signal);
	},
});

// -------------------------------------------------- Linux sandbox tool (kylin-agent rootfs)

const SANDBOX_BASE = "http://127.0.0.1:33990";

async function sandboxCall(pathname, body, signal) {
	const res = await fetch(SANDBOX_BASE + pathname, {
		method: body ? "POST" : "GET",
		headers: body ? { "content-type": "application/json" } : undefined,
		body: body ? JSON.stringify(body) : undefined,
		signal,
	});
	const data = await res.json().catch(() => ({ error: "bad bridge response" }));
	if (data.error) return { exitCode: 1, output: `sandbox bridge: ${data.error}` };
	return data;
}

const linuxTool = defineTool({
	name: "linux",
	description:
		"Run a command in the bundled Linux userland (Termux rootfs in-app: bash, coreutils, grep/sed/awk, apt, …) " +
		"and return rc with combined stdout/stderr (60s cap, output truncated). " +
		"Use this when the Android mksh `bash` tool lacks a program or full GNU behavior. " +
		"If it reports the rootfs missing, say so (install needs a one-time bootstrap).",
	parameters: {
		cmd: { type: "string", description: "The shell command to execute (single line, pipes allowed)." },
	},
	output: {
		schema: {
			type: "object",
			additionalProperties: false,
			properties: {
				result: { type: "string", required: true, description: "rc header + combined output, as returned by the sandbox." },
			},
		},
		render: (_args, value) => [{ type: "text", text: value.result }],
	},
	async execute(args, exec) {
		const cmd = String(args.cmd ?? "").trim();
		if (!cmd) return { result: "(empty command)" };
		const data = await sandboxCall("/linux/exec", { cmd }, exec.signal);
		return { result: String(data.result ?? JSON.stringify(data)) };
	},
});

// -------------------------------------------------- web_* tools: drive the sidebar browser panel
// browser-use semantics on the shell's OWN engine (BrowserOverlay): the
// page the user sees in the panel is the page these tools control.
// Human-in-the-loop: when a login/captcha blocks progress, call web_manual
// and TELL the user to finish it in the panel; the tool returns once the
// page advances.

async function webCall(op, body, signal) {
	const res = await fetch(SANDBOX_BASE + "/web/" + op, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify(body ?? {}),
		signal,
	});
	const data = await res.json().catch(() => ({ error: "bad bridge response" }));
	if (data.error) return `web bridge: ${data.error}`;
	return String(data.result ?? "(空)");
}

const webTools = [
	defineTool({
		name: "web_open",
		description:
			"Open a URL in the sidebar browser panel (the panel is visible to the user — they watch the page load). " +
			"Waits for load completion, then returns url/title/ready state. http/https only.",
		parameters: { url: { type: "string", description: "Full URL (https://... or bare host)." } },
		output: { schema: { type: "object", additionalProperties: false, properties: { result: { type: "string", required: true } } }, render: (_a, v) => [{ type: "text", text: v.result }] },
		async execute(args, exec) {
			return { result: await webCall("open", { url: String(args.url ?? "").trim() }, exec.signal) };
		},
	}),
	defineTool({
		name: "web_state",
		description:
			"Current url/title/readyState/viewport of the sidebar browser page. Cheap — call after any action to verify.",
		parameters: {},
		output: { schema: { type: "object", additionalProperties: false, properties: { result: { type: "string", required: true } } }, render: (_a, v) => [{ type: "text", text: v.result }] },
		async execute(_args, exec) {
			return { result: await webCall("state", {}, exec.signal) };
		},
	}),
	defineTool({
		name: "web_dom",
		description:
			"List up to 20 nodes matching a CSS selector (tag/id/text/value) — the eyes for deciding what to click or type into. " +
			"Use web_state first if nothing matches (page may not be loaded).",
		parameters: { selector: { type: "string", description: "CSS selector, e.g. '#kw', 'input[type=submit]', 'a'." } },
		output: { schema: { type: "object", additionalProperties: false, properties: { result: { type: "string", required: true } } }, render: (_a, v) => [{ type: "text", text: v.result }] },
		async execute(args, exec) {
			return { result: await webCall("dom", { selector: String(args.selector ?? "") }, exec.signal) };
		},
	}),
	defineTool({
		name: "web_click",
		description: "Click the first element matching a CSS selector (scrolls into view first). Verify with web_state/web_dom.",
		parameters: { selector: { type: "string", description: "CSS selector of the element to click." } },
		output: { schema: { type: "object", additionalProperties: false, properties: { result: { type: "string", required: true } } }, render: (_a, v) => [{ type: "text", text: v.result }] },
		async execute(args, exec) {
			return { result: await webCall("click", { selector: String(args.selector ?? "") }, exec.signal) };
		},
	}),
	defineTool({
		name: "web_type",
		description:
			"Type text into an input matching a CSS selector (native setter + input/change events — React/Vue controlled inputs accept it).",
		parameters: {
			selector: { type: "string", description: "CSS selector of the input/textarea." },
			text: { type: "string", description: "Text to type." },
		},
		output: { schema: { type: "object", additionalProperties: false, properties: { result: { type: "string", required: true } } }, render: (_a, v) => [{ type: "text", text: v.result }] },
		async execute(args, exec) {
			return { result: await webCall("type", { selector: String(args.selector ?? ""), text: String(args.text ?? "") }, exec.signal) };
		},
	}),
	defineTool({
		name: "web_manual",
		description:
			"Hand the sidebar browser page to the human and WAIT (up to timeoutS) until they act — use when a login/captcha/verification " +
			"blocks progress. IMPORTANT: before/while calling this, TELL the user in your reply exactly what to do in the panel " +
			"(e.g. 「请在右侧面板完成登录，我会等你」). Returns as soon as the page URL advances, with the new state.",
		parameters: {
			reason: { type: "string", description: "What the user should do, e.g. 'complete login'." },
			timeoutS: { type: "number", description: "Max seconds to wait (10-900, default 300)." },
		},
		output: { schema: { type: "object", additionalProperties: false, properties: { result: { type: "string", required: true } } }, render: (_a, v) => [{ type: "text", text: v.result }] },
		async execute(args, exec) {
			return {
				result: await webCall("manual", {
					reason: String(args.reason ?? "manual operation"),
					timeoutS: Number.isFinite(args.timeoutS) ? args.timeoutS : 300,
				}, exec.signal),
			};
		},
	}),
];

// -------------------------------------------------- DuckDuckGo search provider

function decodeEntities(text) {
	return text
		.replace(/&#x27;|&#39;/g, "'")
		.replace(/&quot;/g, '"')
		.replace(/&lt;/g, "<")
		.replace(/&gt;/g, ">")
		.replace(/&nbsp;/g, " ")
		.replace(/&amp;/g, "&");
}

function stripTags(html) {
	return decodeEntities(html.replace(/<[^>]*>/g, "")).replace(/\s+/g, " ").trim();
}

function parseDdg(html, limit) {
	const results = [];
	const blocks = html.split(/<div[^>]*class="[^"]*result[^"]*"/).slice(1);
	for (const block of blocks) {
		const link = /<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/.exec(block);
		if (!link) continue;
		let url = link[1];
		const uddg = /[?&]uddg=([^&]+)/.exec(url);
		if (uddg) {
			try {
				url = decodeURIComponent(uddg[1]);
			} catch {
				/* keep the raw href */
			}
		}
		if (url.startsWith("//")) url = "https:" + url;
		const snippet = /<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/a>/.exec(block);
		results.push({
			title: stripTags(link[2]) || url,
			url,
			snippet: snippet ? stripTags(snippet[1]).slice(0, 400) : "",
		});
		if (results.length >= limit) break;
	}
	return results;
}

const ddgProvider = {
	id: "kylin-ddg",
	available() {
		return true;
	},
	async search(request, signal) {
		const query = String(request?.query ?? "").trim();
		if (query === "") return { sources: [] };
		const limit = Number.isFinite(request?.maxResults) ? request.maxResults : 8;
		const ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
		// DuckDuckGo's html endpoint throws an anomaly challenge at some egress
		// IPs; Bing's results page is the reliable path through the proxy.
		try {
			const r = await fetch("https://www.bing.com/search?q=" + encodeURIComponent(query), {
				headers: { "user-agent": ua, "accept-language": "zh-CN,zh;q=0.9,en;q=0.8" },
				signal: signal ?? AbortSignal.timeout(20_000),
				redirect: "follow",
			});
			if (r.ok) {
				const sources = parseBing(await r.text(), limit);
				if (sources.length > 0) return { sources };
			}
		} catch {
			/* fall through to DuckDuckGo */
		}
		try {
			const r = await fetch("https://html.duckduckgo.com/html/?q=" + encodeURIComponent(query), {
				headers: { "user-agent": ua },
				signal: signal ?? AbortSignal.timeout(20_000),
				redirect: "follow",
			});
			if (!r.ok) throw new Error(`duckduckgo returned ${r.status}`);
			return { sources: parseDdg(await r.text(), limit) };
		} catch (error) {
			throw new Error(`kylin-ddg search failed: ${error?.message ?? error}`);
		}
	},
};

/** Pull (title, url, snippet) out of a Bing results page. */
function parseBing(html, limit) {
	const results = [];
	const items = html.split(/<li[^>]*class="b_algo"/).slice(1);
	for (const item of items) {
		const link = /<h2[^>]*><a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a><\/h2>/.exec(item);
		if (!link) continue;
		let url = link[1];
		if (url.startsWith("https://www.bing.com/ck/")) {
			const u = /\bu=([^&"]+)/.exec(url);
			if (u) {
				try {
					url = decodeURIComponent(u[1]);
				} catch {
					/* keep the redirect url */
				}
			}
		}
		const snippet = /class="b_caption"[\s\S]*?<p[^>]*>([\s\S]*?)<\/p>/.exec(item) ?? /<p[^>]*class="b_lineclamp[^"]*"[^>]*>([\s\S]*?)<\/p>/.exec(item);
		results.push({
			title: stripTags(link[2]) || url,
			url,
			snippet: snippet ? stripTags(snippet[1]).slice(0, 400) : "",
		});
		if (results.length >= limit) break;
	}
	return results;
}

// --------------------------------------------------------------- composition

function apply(ctx) {
	const web = ctx.get("web");
	if (web !== undefined) {
		try {
			web.registerSearchProvider(ddgProvider);
		} catch {
			/* already registered by another composition */
		}
	}
	const tools = ctx.get("tools");
	if (tools !== undefined) {
		try {
			tools.register(bashTool);
		} catch {
			/* already registered by another composition */
		}
		try {
			tools.register(linuxTool);
		} catch {
			/* already registered by another composition */
		}
		for (const t of webTools) {
			try {
				tools.register(t);
			} catch {
				/* already registered by another composition */
			}
		}
	}
}

export { apply, inject, name };
