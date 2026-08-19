import fs from 'node:fs';
import path from 'node:path';

const projectRoot = process.cwd();
const uaDir = path.join(projectRoot, fs.existsSync(path.join(projectRoot, '.understand-anything')) ? '.understand-anything' : '.ua');
const raw = JSON.parse(fs.readFileSync(path.join(uaDir, 'intermediate', 'batches.json'), 'utf8'));
const batches = Array.isArray(raw) ? raw : raw.batches;

for (const batch of batches.filter(({ batchIndex }) => batchIndex >= 1 && batchIndex <= 37)) {
  const input = {
    projectRoot,
    batchFiles: batch.files.map(({ path: filePath, language, sizeLines, fileCategory }) => ({
      path: filePath,
      language,
      sizeLines,
      fileCategory,
    })),
    batchImportData: batch.batchImportData,
  };
  fs.writeFileSync(path.join(uaDir, 'tmp', `ua-file-analyzer-input-${batch.batchIndex}.json`), `${JSON.stringify(input, null, 2)}\n`);
}
