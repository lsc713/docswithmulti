import fs from "node:fs";
import { spawnSync } from "node:child_process";

const projectRoot = process.cwd();
const batchesPath = `${projectRoot}/.ua/intermediate/batches.json`;
const extractor = "/Users/juho/.understand-anything/repo/understand-anything-plugin/skills/understand/extract-structure.mjs";
const raw = JSON.parse(fs.readFileSync(batchesPath, "utf8"));
const batches = Array.isArray(raw) ? raw : raw.batches;

for (const batch of batches.filter(({ batchIndex }) => batchIndex >= 38 && batchIndex <= 73)) {
  const inputPath = `${projectRoot}/.ua/tmp/ua-file-analyzer-input-${batch.batchIndex}.json`;
  const outputPath = `${projectRoot}/.ua/tmp/ua-file-extract-results-${batch.batchIndex}.json`;
  fs.writeFileSync(inputPath, JSON.stringify({
    projectRoot,
    batchFiles: batch.files.map(({ path, language, sizeLines, fileCategory }) => ({ path, language, sizeLines, fileCategory })),
    batchImportData: batch.batchImportData,
  }, null, 2));
  const result = spawnSync(process.execPath, [extractor, inputPath, outputPath], { encoding: "utf8" });
  if (result.status !== 0 || !fs.existsSync(outputPath) || fs.statSync(outputPath).size === 0) {
    throw new Error(`batch ${batch.batchIndex}: extractor failed (${result.status})\n${result.stderr}`);
  }
  const extracted = JSON.parse(fs.readFileSync(outputPath, "utf8"));
  if (!extracted.scriptCompleted) throw new Error(`batch ${batch.batchIndex}: scriptCompleted != true`);
  console.log(`${batch.batchIndex}: analyzed=${extracted.filesAnalyzed}, skipped=${extracted.filesSkipped?.length ?? 0}`);
}
