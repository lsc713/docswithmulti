import fs from "node:fs";

const graph = JSON.parse(fs.readFileSync(".ua/intermediate/assembled-graph.json", "utf8"));
const fileTypes = new Set(["file", "config", "document", "service", "pipeline", "table", "schema", "resource", "endpoint"]);
const fileNodes = graph.nodes.filter(node => fileTypes.has(node.type));
const ids = new Set(fileNodes.map(node => node.id));
const allEdges = graph.edges.filter(edge => ids.has(edge.source) && ids.has(edge.target));
const importEdges = allEdges.filter(edge => edge.type === "imports");

fs.writeFileSync(".ua/tmp/ua-arch-input.json", JSON.stringify({ fileNodes, importEdges, allEdges }, null, 2) + "\n");
console.log(JSON.stringify({ fileNodes: fileNodes.length, importEdges: importEdges.length, allEdges: allEdges.length }));
