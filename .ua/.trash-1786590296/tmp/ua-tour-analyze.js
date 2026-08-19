#!/usr/bin/env node
const fs = require('fs');

try {
  const inputPath = process.argv[2];
  const outputPath = process.argv[3];
  if (!inputPath || !outputPath) throw new Error('usage: ua-tour-analyze.js <input> <output>');
  const input = JSON.parse(fs.readFileSync(inputPath, 'utf8'));
  const nodes = input.nodes || [];
  const edges = input.edges || [];
  const nodeById = new Map(nodes.map(node => [node.id, node]));
  const knownEdges = edges.filter(edge => nodeById.has(edge.source) && nodeById.has(edge.target));
  const fanIn = new Map(nodes.map(node => [node.id, 0]));
  const fanOut = new Map(nodes.map(node => [node.id, 0]));
  for (const edge of knownEdges) {
    fanIn.set(edge.target, fanIn.get(edge.target) + 1);
    fanOut.set(edge.source, fanOut.get(edge.source) + 1);
  }
  const rank = (counts, field) => nodes
    .map(node => ({id: node.id, [field]: counts.get(node.id), name: node.name}))
    .sort((a, b) => b[field] - a[field] || a.id.localeCompare(b.id))
    .slice(0, 20);
  const fanInRanking = rank(fanIn, 'fanIn');
  const fanOutRanking = rank(fanOut, 'fanOut');

  const codeNodes = nodes.filter(node => node.type === 'file');
  const sortedFanOut = codeNodes.map(node => fanOut.get(node.id)).sort((a, b) => a - b);
  const highFanOut = sortedFanOut[Math.max(0, Math.floor(sortedFanOut.length * 0.9))] || 0;
  const sortedFanIn = codeNodes.map(node => fanIn.get(node.id)).sort((a, b) => a - b);
  const lowFanIn = sortedFanIn[Math.max(0, Math.floor(sortedFanIn.length * 0.25))] || 0;
  const entryNames = /^(index\.(ts|js)|main\.(ts|js|go|py|rs|cpp|c)|app\.(ts|js|py)|server\.(ts|js)|mod\.rs|manage\.py|wsgi\.py|asgi\.py|run\.py|__main__\.py|Application\.java|Main\.java|Program\.cs|config\.ru|index\.php|App\.swift|Application\.kt)$/i;
  const entryPointCandidates = nodes.map(node => {
    let score = 0;
    const filePath = node.filePath || '';
    if (node.type === 'file') {
      if (entryNames.test(node.name || '')) score += 3;
      if (filePath.split('/').length <= 2) score += 1;
      if (fanOut.get(node.id) >= highFanOut) score += 1;
      if (fanIn.get(node.id) <= lowFanIn) score += 1;
    } else if (node.type === 'document') {
      if (filePath === 'README.md') score += 5;
      else if (/^[^/]+\.md$/i.test(filePath)) score += 2;
    }
    return {id: node.id, score, name: node.name, summary: node.summary, type: node.type};
  }).filter(x => x.score > 0).sort((a, b) => b.score - a.score || a.id.localeCompare(b.id)).slice(0, 5);

  const topCodeEntry = entryPointCandidates.find(x => x.type === 'file') || codeNodes
    .map(node => ({node, score: fanOut.get(node.id)}))
    .sort((a, b) => b.score - a.score)[0]?.node;
  const startNode = topCodeEntry?.id || topCodeEntry;
  const adjacency = new Map();
  for (const edge of knownEdges) {
    if (!['imports', 'calls'].includes(edge.type)) continue;
    if (!adjacency.has(edge.source)) adjacency.set(edge.source, []);
    adjacency.get(edge.source).push(edge.target);
  }
  const order = [], depthMap = {}, byDepth = {};
  if (startNode) {
    const queue = [startNode];
    depthMap[startNode] = 0;
    for (let i = 0; i < queue.length; i++) {
      const id = queue[i];
      order.push(id);
      const depth = depthMap[id];
      (byDepth[depth] ||= []).push(id);
      for (const target of adjacency.get(id) || []) {
        if (depthMap[target] !== undefined) continue;
        depthMap[target] = depth + 1;
        queue.push(target);
      }
    }
  }

  const compact = node => ({id: node.id, name: node.name, type: node.type, summary: node.summary});
  const nonCodeFiles = {
    documentation: nodes.filter(n => n.type === 'document').map(compact),
    infrastructure: nodes.filter(n => ['service', 'pipeline', 'resource'].includes(n.type)).map(compact),
    data: nodes.filter(n => ['table', 'schema', 'endpoint'].includes(n.type)).map(compact),
    config: nodes.filter(n => n.type === 'config').map(compact)
  };

  const relationPairs = new Map();
  for (const edge of knownEdges.filter(e => ['imports', 'calls'].includes(e.type))) {
    relationPairs.set(`${edge.source}\0${edge.target}`, true);
  }
  const clusters = [];
  const clustered = new Set();
  for (const edge of knownEdges.filter(e => ['imports', 'calls'].includes(e.type))) {
    if (!relationPairs.has(`${edge.target}\0${edge.source}`)) continue;
    const key = [edge.source, edge.target].sort().join('\0');
    if (clustered.has(key)) continue;
    clustered.add(key);
    const members = new Set([edge.source, edge.target]);
    for (const candidate of nodes) {
      if (members.size >= 5 || members.has(candidate.id)) continue;
      const links = [...members].filter(member => relationPairs.has(`${candidate.id}\0${member}`) || relationPairs.has(`${member}\0${candidate.id}`)).length;
      if (links >= 2) members.add(candidate.id);
    }
    const memberIds = [...members];
    const edgeCount = knownEdges.filter(e => memberIds.includes(e.source) && memberIds.includes(e.target)).length;
    clusters.push({nodes: memberIds, edgeCount});
  }
  clusters.sort((a, b) => b.edgeCount - a.edgeCount);

  const nodeSummaryIndex = Object.fromEntries(nodes.map(node => [node.id, {name: node.name, type: node.type, summary: node.summary}]));
  const result = {
    scriptCompleted: true,
    entryPointCandidates,
    fanInRanking,
    fanOutRanking,
    bfsTraversal: {startNode: startNode || null, order, depthMap, byDepth},
    nonCodeFiles,
    clusters: clusters.slice(0, 10),
    layers: {count: (input.layers || []).length, list: input.layers || []},
    nodeSummaryIndex,
    totalNodes: nodes.length,
    totalEdges: edges.length
  };
  fs.writeFileSync(outputPath, JSON.stringify(result, null, 2));
  process.exit(0);
} catch (error) {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exit(1);
}
