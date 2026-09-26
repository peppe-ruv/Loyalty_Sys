#!/usr/bin/env node
import { readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "yaml";

const here = resolve(fileURLToPath(import.meta.url), "..");
const repoRoot = resolve(here, "..");
const apiDir = join(repoRoot, "contracts", "api");

const files = [
  "ingestion.openapi.yaml", "member.openapi.yaml", "campaign.openapi.yaml",
  "wallet.openapi.yaml", "insight.openapi.yaml", "reward.openapi.yaml",
  "gamification.openapi.yaml", "engagement.openapi.yaml", "portal.openapi.yaml"
];

function getBaseRef() {
    try {
        const tag = execSync("git describe --tags --abbrev=0", { encoding: "utf8", stdio: ["pipe", "pipe", "ignore"] }).trim();
        return tag || "origin/main";
    } catch {
        return "origin/main";
    }
}

function getBaseFileContent(filename, ref) {
    try {
        const content = execSync(`git show ${ref}:contracts/api/${filename}`, { encoding: "utf8", stdio: ["pipe", "pipe", "ignore"] });
        return content;
    } catch {
        return null;
    }
}

export function checkBreakingChanges(baseObj, currentObj, pathStr = "", errors = []) {
    if (!baseObj) return errors;

    if (Array.isArray(baseObj)) {
        if (!Array.isArray(currentObj)) {
            errors.push(`${pathStr}: expected array, got ${typeof currentObj}`);
        }
        return errors;
    }

    if (typeof baseObj === "object") {
        if (typeof currentObj !== "object" || currentObj === null) {
            errors.push(`${pathStr}: expected object, got ${currentObj === null ? "null" : typeof currentObj}`);
            return errors;
        }

        for (const key of Object.keys(baseObj)) {
            const nextPath = pathStr ? `${pathStr}.${key}` : key;

            if (!(key in currentObj)) {
                if (nextPath.match(/\.responses\.\d{3}\.content\..+\.schema\.properties\./)) {
                    errors.push(`Removed response property: ${nextPath}`);
                }
                else if (nextPath.match(/^paths\.[^.]+(\.[^.]+)?$/)) {
                    errors.push(`Removed path or operation: ${nextPath}`);
                }
                else if (nextPath.startsWith("components.schemas.")) {
                     errors.push(`Removed schema or property: ${nextPath}`);
                }
                continue;
            }

            if (key === "enum" && Array.isArray(baseObj[key]) && Array.isArray(currentObj[key])) {
                for (const val of baseObj[key]) {
                    if (!currentObj[key].includes(val)) {
                        errors.push(`Narrowed enum at ${nextPath}: removed value '${val}'`);
                    }
                }
            }

            if (key === "type" && baseObj[key] !== currentObj[key]) {
                errors.push(`Type changed at ${nextPath}: from ${baseObj[key]} to ${currentObj[key]}`);
            }

            if (key === "required" && Array.isArray(currentObj[key]) && Array.isArray(baseObj[key])) {
                if (nextPath.match(/requestBody|components\.schemas/)) {
                    for (const req of currentObj[key]) {
                        if (!baseObj[key].includes(req)) {
                            errors.push(`Added required request field at ${nextPath}: '${req}'`);
                        }
                    }
                }
            } else if (key === "required" && Array.isArray(currentObj[key]) && !baseObj[key]) {
                 if (nextPath.match(/requestBody|components\.schemas/)) {
                    errors.push(`Added required request field at ${nextPath}: ${currentObj[key].join(", ")}`);
                 }
            }

            checkBreakingChanges(baseObj[key], currentObj[key], nextPath, errors);
        }
    }
    return errors;
}

if (import.meta.url.endsWith(process.argv[1])) {
    const baseRef = getBaseRef();
    console.log(`Checking API compatibility against base ref: ${baseRef}`);

    let allErrors = [];

    for (const file of files) {
        const filePath = join(apiDir, file);
        if (!existsSync(filePath)) {
             console.warn(`File ${file} does not exist in the current tree, skipping.`);
             continue;
        }

        const currentContent = readFileSync(filePath, "utf8");
        const baseContent = getBaseFileContent(file, baseRef);

        if (!baseContent) {
            console.log(`No base content found for ${file}, assuming it's new. (OK)`);
            continue;
        }

        let baseApi, currentApi;
        try {
            baseApi = yaml.parse(baseContent);
            currentApi = yaml.parse(currentContent);
        } catch (err) {
            console.error(`Failed to parse YAML for ${file}: ${err.message}`);
            allErrors.push(`Parse error in ${file}`);
            continue;
        }

        const errors = checkBreakingChanges(baseApi, currentApi, "", []);
        if (errors.length > 0) {
             console.log(`Breaking changes found in ${file}:`);
             for (const error of errors) {
                  console.log(`  - ${error}`);
             }
             allErrors.push(...errors);
        } else {
             console.log(`${file}: OK`);
        }
    }

    if (allErrors.length > 0) {
        console.error(`\nFailed API compatibility check. ${allErrors.length} breaking changes found.`);
        process.exit(1);
    } else {
        console.log("\nAPI compatibility check passed.");
    }
}
