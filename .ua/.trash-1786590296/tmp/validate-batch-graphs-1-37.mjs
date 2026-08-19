import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const intermediate = path.join(root, '.ua', 'intermediate');
const raw = JSON.parse(fs.readFileSync(path.join(intermediate, 'batches.json'), 'utf8'));
const batches = Array.isArray(raw) ? raw : raw.batches;
const allowedNodeTypes = new Set(['file', 'function', 'class', 'config', 'document', 'service', 'table', 'endpoint', 'pipeline', 'schema', 'resource']);
const allowedEdgeTypes = new Set(['contains', 'imports', 'calls', 'inherits', 'implements', 'exports', 'depends_on', 'tested_by', 'configures', 'documents', 'deploys', 'migrates', 'triggers', 'defines_schema', 'serves', 'provisions', 'routes', 'related']);
const summary = [];

for (const batch of batches.filter(({ batchIndex }) => batchIndex >= 1 && batchIndex <= 37)) {
  const pattern = new RegExp(`^batch-${batch.batchIndex}(?:-part-(\\d+))?\\.json$`);
  const filenames = fs.readdirSync(intermediate).filter(name => pattern.test(name)).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  if (!filenames.length) throw new Error(`Batch ${batch.batchIndex}: output missing`);
  const fragments = filenames.map(name => JSON.parse(fs.readFileSync(path.join(intermediate, name), 'utf8')));
  const nodes = fragments.flatMap(fragment => fragment.nodes);
  const edges = fragments.flatMap(fragment => fragment.edges);
  const ids = new Set(nodes.map(node => node.id));
  if (ids.size !== nodes.length) throw new Error(`Batch ${batch.batchIndex}: duplicate node id`);
  const batchPaths = new Set(batch.files.map(file => file.path));
  const covered = new Set(nodes.filter(node => node.filePath && batchPaths.has(node.filePath)).map(node => node.filePath));
  if (covered.size !== batchPaths.size) throw new Error(`Batch ${batch.batchIndex}: file coverage ${covered.size}/${batchPaths.size}`);
  const externalFiles = new Set(Object.values(batch.batchImportData).flat());
  const neighborSymbols = new Map();
  for (const neighbors of Object.values(batch.neighborMap || {})) {
    for (const neighbor of neighbors || []) neighborSymbols.set(neighbor.path, new Set(neighbor.symbols || []));
  }
  for (const node of nodes) {
    if (!allowedNodeTypes.has(node.type) || !node.id || !node.name || !node.summary || !Array.isArray(node.tags) || node.tags.length < 3 || !['simple', 'moderate', 'complex'].includes(node.complexity)) throw new Error(`Batch ${batch.batchIndex}: invalid node ${node.id}`);
    if (['function', 'class'].includes(node.type) && (!Array.isArray(node.lineRange) || node.lineRange.length !== 2)) throw new Error(`Batch ${batch.batchIndex}: missing lineRange ${node.id}`);
  }
  for (const edge of edges) {
    if (!allowedEdgeTypes.has(edge.type) || edge.direction !== 'forward' || edge.source === edge.target || !ids.has(edge.source)) throw new Error(`Batch ${batch.batchIndex}: invalid edge ${JSON.stringify(edge)}`);
    if (ids.has(edge.target)) continue;
    const fileMatch = edge.target.match(/^file:(.+)$/);
    if (fileMatch && externalFiles.has(fileMatch[1])) continue;
    const symbolMatch = edge.target.match(/^(?:function|class):(.+):([^:]+)$/);
    if (symbolMatch && neighborSymbols.get(symbolMatch[1])?.has(symbolMatch[2])) continue;
    throw new Error(`Batch ${batch.batchIndex}: unresolved edge target ${edge.target}`);
  }
  const expectedImports = batch.files.reduce((sum, file) => sum + (batch.batchImportData[file.path]?.length || 0), 0);
  const imports = edges.filter(edge => edge.type === 'imports').length;
  if (imports !== expectedImports) throw new Error(`Batch ${batch.batchIndex}: imports ${imports}/${expectedImports}`);
  summary.push({ batchIndex: batch.batchIndex, files: batch.files.length, parts: filenames.length, nodes: nodes.length, edges: edges.length, imports });
}

const unexpected = fs.readdirSync(intermediate).filter(name => /^batch-(?:fused|merged|\d+-\d+)|^batches-/.test(name));
if (unexpected.length) throw new Error(`Unexpected output names: ${unexpected.join(', ')}`);
process.stdout.write(`${JSON.stringify(summary)}\n`);
