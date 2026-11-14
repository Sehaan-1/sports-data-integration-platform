import path from "node:path";
import { fileURLToPath } from "node:url";
import { mkdir, access, readFile, writeFile } from "node:fs/promises";
import { brotliDecompressSync } from "node:zlib";
import { execFileSync } from "node:child_process";
import chromium from "@sparticuz/chromium";
import { chromium as playwright } from "playwright-core";

/** The npm-distributed browser makes checks reproducible even in a sandbox without Chrome.
 * CHROME_PATH can point to an existing Chrome installation on other development machines.
 * Missing Linux NSS libraries are unpacked from that same pinned npm package, never apt/CDNs.
 */
export async function launchBrowser() {
  if (process.env.CHROME_PATH)
    return playwright.launch({
      executablePath: process.env.CHROME_PATH,
      headless: true,
      args: ["--no-sandbox", "--disable-dev-shm-usage"],
    });
  const executablePath = await chromium.executablePath();
  const env = { ...process.env };
  if (process.platform === "linux" && process.arch === "x64") {
    const libraryRoot = fileURLToPath(
      new URL("../.cache/chromium-libs/", import.meta.url),
    );
    const marker = path.join(libraryRoot, "lib/libnspr4.so");
    try {
      await access(marker);
    } catch {
      await mkdir(libraryRoot, { recursive: true });
      const archive = fileURLToPath(
        new URL(
          "../node_modules/@sparticuz/chromium/bin/al2023.tar.br",
          import.meta.url,
        ),
      );
      const tar = path.join(libraryRoot, "al2023.tar");
      await writeFile(tar, brotliDecompressSync(await readFile(archive)));
      execFileSync("tar", ["-xf", tar, "-C", libraryRoot]);
    }
    env.LD_LIBRARY_PATH = [path.join(libraryRoot, "lib"), env.LD_LIBRARY_PATH]
      .filter(Boolean)
      .join(":");
  }
  return playwright.launch({
    executablePath,
    headless: true,
    env,
    args: chromium.args.filter(
      (argument) =>
        ![
          "--disable-web-security",
          "--allow-running-insecure-content",
          "--single-process",
        ].includes(argument),
    ),
  });
}
