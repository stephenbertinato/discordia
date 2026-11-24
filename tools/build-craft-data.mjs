#!/usr/bin/env node
/**
 * Builds ahflip/src/main/resources/recipes.json and upgrades.json from a
 * NotEnoughUpdates-REPO checkout (MIT).
 *
 * Usage:
 *   git clone --depth 1 --filter=blob:none --sparse \
 *     https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO.git .neu-tmp
 *   git -C .neu-tmp sparse-checkout set items constants
 *   node tools/build-craft-data.mjs .neu-tmp
 *
 * The output is committed so the mod needs no network access for recipes and no
 * third-party service at runtime.
 */
import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2] || '.neu-tmp';
const outDir = path.resolve('ahflip/src/main/resources');
const SLOTS = ['A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3'];

if (!fs.existsSync(path.join(root, 'items'))) {
  console.error(`No items/ directory under ${root}`);
  process.exit(1);
}

// --- recipes -----------------------------------------------------------------
const recipes = {};
let scanned = 0;
for (const file of fs.readdirSync(path.join(root, 'items'))) {
  if (!file.endsWith('.json')) continue;
  scanned++;
  let item;
  try {
    item = JSON.parse(fs.readFileSync(path.join(root, 'items', file), 'utf8'));
  } catch {
    continue;
  }
  if (!item.recipe || typeof item.recipe !== 'object') continue;
  const id = item.internalname || file.replace(/\.json$/, '');
  const grid = SLOTS.map(slot => {
    const value = item.recipe[slot];
    if (!value || typeof value !== 'string') return '';
    // Normalise "ID:count"; NEU omits the count for single items.
    const [name, count] = value.split(':');
    const parsed = Number.parseInt(count ?? '1', 10);
    if (!name) return '';
    return `${name}:${Number.isFinite(parsed) && parsed > 0 ? parsed : 1}`;
  });
  if (grid.every(cell => cell === '')) continue;
  recipes[id] = grid;
}

// Every internal name used as an ingredient, so the loader can tell an unknown
// id apart from a non-craftable one.
const ingredientIds = new Set();
for (const grid of Object.values(recipes)) {
  for (const cell of grid) {
    if (cell) ingredientIds.add(cell.split(':')[0]);
  }
}

fs.writeFileSync(
  path.join(outDir, 'recipes.json'),
  JSON.stringify({
    _source: 'NotEnoughUpdates-REPO items/*.json, field "recipe" (MIT). 9 slots: A1..C3.',
    _generated: new Date().toISOString().slice(0, 10),
    recipes,
  })
);

// --- star costs --------------------------------------------------------------
const essence = JSON.parse(fs.readFileSync(path.join(root, 'constants/essencecosts.json'), 'utf8'));
const stars = {};
for (const [id, entry] of Object.entries(essence)) {
  const type = (entry.type || '').toUpperCase();
  if (!type || type === 'NONE') continue;
  const costs = [];
  const coins = [];
  for (let level = 1; level <= 5; level++) {
    const amount = Number(entry[String(level)] ?? 0);
    if (!Number.isFinite(amount) || amount <= 0) {
      costs.length = 0; // a gap means the star line is not measurable
      break;
    }
    costs.push(amount);
    const paid = entry.items?.[String(level)];
    coins.push(Array.isArray(paid) ? paid.reduce((sum, token) => {
      const [name, count] = String(token).split(':');
      return name === 'SKYBLOCK_COIN' ? sum + Number(count ?? 0) : sum;
    }, 0) : 0);
  }
  if (costs.length === 5) stars[id] = { essence: type, cost: costs, coins };
}

// --- reforge stones ----------------------------------------------------------
const stones = JSON.parse(fs.readFileSync(path.join(root, 'constants/reforgestones.json'), 'utf8'));
const reforges = {};
for (const [stoneId, entry] of Object.entries(stones)) {
  const name = entry.reforgeName;
  if (!name || !entry.reforgeType?.startsWith('blacksmith')) continue;
  // Only stones that are actually items you buy; skip coin-only reforge services.
  reforges[name.toLowerCase()] = { stone: stoneId, coins: entry.reforgeCosts || {} };
}

fs.writeFileSync(
  path.join(outDir, 'upgrades.json'),
  JSON.stringify({
    _source: 'NEU constants/essencecosts.json + reforgestones.json (MIT).',
    _generated: new Date().toISOString().slice(0, 10),
    stars,
    reforges,
  })
);

const recipeBytes = fs.statSync(path.join(outDir, 'recipes.json')).size;
const upgradeBytes = fs.statSync(path.join(outDir, 'upgrades.json')).size;
console.log(`scanned ${scanned} item files`);
console.log(`recipes: ${Object.keys(recipes).length} outputs, ${ingredientIds.size} distinct ingredients, ${recipeBytes} bytes`);
console.log(`stars:   ${Object.keys(stars).length} items`);
console.log(`reforges:${Object.keys(reforges).length} stones, ${upgradeBytes} bytes`);
