const fs = require("node:fs");
const path = require("node:path");

function fail(error) {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
}

function commonDirectoryPrefix(paths) {
  if (!paths.length) return [];
  const dirs = paths.map(p => p.split("/").slice(0, -1));
  const prefix = [];
  for (let i = 0; i < Math.min(...dirs.map(x => x.length)); i++) {
    if (dirs.every(x => x[i] === dirs[0][i])) prefix.push(dirs[0][i]);
    else break;
  }
  return prefix;
}

function patternFor(name, node) {
  const p = node.filePath.toLowerCase();
  const base = path.basename(p);
  if (/\.(test|spec)\.|test_.*\.py$|_test\.go$|test\.java$|tests\.cs$/.test(base)) return "test";
  if (/\.d\.ts$/.test(base) || /\.(graphql|gql|proto)$/.test(base)) return "types";
  if (/application\.java$|program\.cs$|(^|\/)cmd\/.*\/main\.go$|(^|\/)src\/(main|lib)\.rs$/.test(p)) return "entry";
  if (/dockerfile|docker-compose|\.tf(vars)?$|makefile$/.test(p)) return "infrastructure";
  if (/\.github\/workflows|\.gitlab-ci|jenkinsfile/.test(p)) return "ci-cd";
  if (/\.sql$/.test(p)) return "data";
  if (/\.(md|rst)$/.test(p)) return "documentation";
  const checks = [
    [/(^|\/)(routes?|api|controllers?|endpoints?|handlers?|serializers?)(\/|$)/, "api"],
    [/(^|\/)(services?|core|lib|domain|logic|internal|signals|jobs|channels)(\/|$)/, "service"],
    [/(^|\/)(models?|db|data|persistence|repositories|entities|migrations|sql|database|schema)(\/|$)/, "data"],
    [/(^|\/)(components?|views?|pages?|ui|layouts?|screens?)(\/|$)/, "ui"],
    [/(^|\/)(middleware|plugins|interceptors|guards)(\/|$)/, "middleware"],
    [/(^|\/)(utils?|helpers?|common|shared|tools|pkg)(\/|$)/, "utility"],
    [/(^|\/)(config|constants|env|settings|management|commands)(\/|$)/, "config"],
    [/(^|\/)(__tests__|tests?|specs?)(\/|$)/, "test"],
    [/(^|\/)(types?|interfaces?|schemas?|contracts?|dtos?|request|response)(\/|$)/, "types"],
    [/(^|\/)(hooks)(\/|$)/, "hooks"], [/(^|\/)(store|state|reducers|actions|slices)(\/|$)/, "state"],
    [/(^|\/)(assets|static|public)(\/|$)/, "assets"],
    [/(^|\/)(docs|documentation|wiki)(\/|$)/, "documentation"],
    [/(^|\/)(deploy|deployment|infra|infrastructure|k8s|kubernetes|helm|charts|terraform|tf|docker)(\/|$)/, "infrastructure"],
    [/(^|\/)(\.github|\.gitlab|\.circleci)(\/|$)/, "ci-cd"],
  ];
  for (const [regex, label] of checks) if (regex.test(`/${p}/`) || regex.test(`/${name}/`)) return label;
  if (["build.gradle", "pom.xml", "package.json", "go.mod", "cargo.toml", "gemfile", "composer.json"].includes(base)) return "config";
  return "unclassified";
}

try {
  const [inputPath, outputPath] = process.argv.slice(2);
  if (!inputPath || !outputPath) throw new Error("usage: node ua-arch-analyze.js INPUT OUTPUT");
  const input = JSON.parse(fs.readFileSync(inputPath, "utf8"));
  const { fileNodes, importEdges, allEdges } = input;
  const byId = new Map(fileNodes.map(n => [n.id, n]));
  const prefix = commonDirectoryPrefix(fileNodes.map(n => n.filePath));
  const groupOf = new Map();
  const directoryGroups = {};
  for (const node of fileNodes) {
    const parts = node.filePath.split("/");
    const remaining = parts.slice(prefix.length);
    let group = remaining.length > 1 ? remaining[0] : "root";
    if (prefix.length && remaining.length <= 1) group = "root";
    groupOf.set(node.id, group);
    (directoryGroups[group] ||= []).push(node.id);
  }
  if (Object.keys(directoryGroups).length === 1) {
    const regrouped = {};
    for (const node of fileNodes) {
      const group = patternFor("root", node) || path.extname(node.filePath).slice(1) || "root";
      groupOf.set(node.id, group);
      (regrouped[group] ||= []).push(node.id);
    }
    Object.keys(directoryGroups).forEach(k => delete directoryGroups[k]);
    Object.assign(directoryGroups, regrouped);
  }
  const nodeTypeGroups = {};
  for (const node of fileNodes) (nodeTypeGroups[node.type] ||= []).push(node.id);
  const fanIn = Object.fromEntries(fileNodes.map(n => [n.id, 0]));
  const fanOut = Object.fromEntries(fileNodes.map(n => [n.id, 0]));
  const adjacency = Object.fromEntries(fileNodes.map(n => [n.id, []]));
  const inter = new Map();
  for (const edge of importEdges) {
    if (!byId.has(edge.source) || !byId.has(edge.target)) continue;
    fanOut[edge.source]++; fanIn[edge.target]++; adjacency[edge.source].push(edge.target);
    const key = `${groupOf.get(edge.source)}\0${groupOf.get(edge.target)}`;
    inter.set(key, (inter.get(key) || 0) + 1);
  }
  const interGroupImports = [...inter].filter(([key]) => key.split("\0")[0] !== key.split("\0")[1]).map(([key, count]) => { const [from, to] = key.split("\0"); return { from, to, count }; }).sort((a,b)=>b.count-a.count);
  const groupDependencies = {};
  for (const group of Object.keys(directoryGroups)) groupDependencies[group] = { importsFrom: new Set(), importedBy: new Set() };
  for (const { from, to } of interGroupImports) { groupDependencies[from].importsFrom.add(to); groupDependencies[to].importedBy.add(from); }
  const intraGroupDensity = {};
  for (const group of Object.keys(directoryGroups)) {
    let internalEdges = 0, totalEdges = 0;
    for (const edge of importEdges) {
      const sg = groupOf.get(edge.source), tg = groupOf.get(edge.target);
      if (sg === group || tg === group) totalEdges++;
      if (sg === group && tg === group) internalEdges++;
    }
    intraGroupDensity[group] = { internalEdges, totalEdges, density: totalEdges ? internalEdges / totalEdges : 0 };
  }
  const cross = new Map();
  for (const edge of allEdges) {
    const source = byId.get(edge.source), target = byId.get(edge.target);
    if (!source || !target || source.type === target.type) continue;
    const key = `${source.type}\0${target.type}\0${edge.type}`;
    cross.set(key, (cross.get(key) || 0) + 1);
  }
  const crossCategoryEdges = [...cross].map(([key,count])=>{const [fromType,toType,edgeType]=key.split("\0"); return {fromType,toType,edgeType,count};}).sort((a,b)=>b.count-a.count);
  const patternMatches = {};
  for (const [group, ids] of Object.entries(directoryGroups)) {
    const labels = new Map();
    for (const id of ids) { const label = patternFor(group, byId.get(id)); labels.set(label, (labels.get(label)||0)+1); }
    patternMatches[group] = [...labels].sort((a,b)=>b[1]-a[1])[0]?.[0] || "unclassified";
  }
  const infraFiles = fileNodes.filter(n => ["service","resource","pipeline"].includes(n.type) || patternFor(groupOf.get(n.id),n)==="infrastructure" || patternFor(groupOf.get(n.id),n)==="ci-cd").map(n=>n.filePath);
  const deploymentTopology = {
    hasDockerfile: infraFiles.some(p=>/dockerfile/i.test(p)), hasCompose: infraFiles.some(p=>/docker-compose/i.test(p)),
    hasK8s: infraFiles.some(p=>/(^|\/)(k8s|kubernetes|helm|charts)(\/|$)/i.test(p)), hasTerraform: infraFiles.some(p=>/\.tf(vars)?$/i.test(p)),
    hasCI: fileNodes.some(n=>n.type==="pipeline" || /\.github\/workflows|\.gitlab-ci|jenkinsfile/i.test(n.filePath)), infraFiles,
  };
  const dataPipeline = {
    schemaFiles: fileNodes.filter(n=>n.type==="schema" || /schema\.(sql|graphql|gql|proto|prisma)$/i.test(n.filePath)).map(n=>n.filePath),
    migrationFiles: fileNodes.filter(n=>/migrations?\/.*\.sql$|db\/migration\/.*\.sql$/i.test(n.filePath)).map(n=>n.filePath),
    dataModelFiles: fileNodes.filter(n=>/\/(model|entity|repository|persistence)s?\/|(?:Entity|Repository)\.java$/i.test(n.filePath)).map(n=>n.filePath),
    apiHandlerFiles: fileNodes.filter(n=>/\/(controller|handler|routes?|api)s?\/|(?:Controller|Handler)\.java$/i.test(n.filePath)).map(n=>n.filePath),
  };
  const groupsWithDocs = new Set(fileNodes.filter(n=>n.type==="document").map(n=>groupOf.get(n.id)));
  const allGroups = Object.keys(directoryGroups);
  const docCoverage = { groupsWithDocs: groupsWithDocs.size, totalGroups: allGroups.length, coverageRatio: allGroups.length ? groupsWithDocs.size/allGroups.length : 0, undocumentedGroups: allGroups.filter(g=>!groupsWithDocs.has(g)) };
  const dependencyDirection = [];
  const pairs = new Set(interGroupImports.map(x=>[x.from,x.to].sort().join("\0")));
  for (const pair of pairs) { const [a,b]=pair.split("\0"); const ab=inter.get(`${a}\0${b}`)||0, ba=inter.get(`${b}\0${a}`)||0; if(ab!==ba) dependencyDirection.push(ab>ba?{dependent:a,dependsOn:b}:{dependent:b,dependsOn:a}); }
  const result = {
    scriptCompleted: true, commonPathPrefix: prefix.join("/"), directoryGroups, nodeTypeGroups, importAdjacency: adjacency,
    groupDependencies: Object.fromEntries(Object.entries(groupDependencies).map(([k,v])=>[k,{importsFrom:[...v.importsFrom],importedBy:[...v.importedBy]}])),
    crossCategoryEdges, interGroupImports, intraGroupDensity, patternMatches, deploymentTopology, dataPipeline, docCoverage, dependencyDirection,
    fileStats: { totalFileNodes:fileNodes.length, filesPerGroup:Object.fromEntries(Object.entries(directoryGroups).map(([k,v])=>[k,v.length])), nodeTypeCounts:Object.fromEntries(Object.entries(nodeTypeGroups).map(([k,v])=>[k,v.length])) },
    fileFanIn: fanIn, fileFanOut: fanOut,
  };
  fs.writeFileSync(outputPath, JSON.stringify(result, null, 2)+"\n");
} catch (error) { fail(error); }
