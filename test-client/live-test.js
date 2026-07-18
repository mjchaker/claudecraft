const mineflayer = require('mineflayer');

const bot = mineflayer.createBot({
  host: 'localhost',
  port: 25565,
  username: 'Tester',
  version: '1.20.4',
});

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let lastBotChatAt = 0;
bot.on('message', (jsonMsg) => {
  const text = jsonMsg.toString();
  log('CHAT |', text);
  if (text.startsWith('<Claude>') || text.startsWith('[Claude]')) {
    lastBotChatAt = Date.now();
  }
});
bot.on('kicked', (r) => log('KICKED |', JSON.stringify(r)));
bot.on('error', (e) => log('ERROR |', e.message));

function waitForChat(regex, timeoutMs) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      bot.removeListener('message', handler);
      resolve(false);
    }, timeoutMs);
    const handler = (jsonMsg) => {
      if (regex.test(jsonMsg.toString())) {
        clearTimeout(timer);
        bot.removeListener('message', handler);
        resolve(true);
      }
    };
    bot.on('message', handler);
  });
}

bot.once('spawn', async () => {
  log('== spawned in world');
  await wait(2000);

  log('== TEST 1: chat trigger');
  bot.chat('@claude reply with a short greeting');
  log('   reply received:', await waitForChat(/\[Claude\]/, 90000));

  log('== TEST 2: /ask');
  bot.chat('/ask what is 2+2? answer with just the number');
  log('   reply received:', await waitForChat(/\[Claude\]/, 90000));

  log('== TEST 3: /bot spawn');
  bot.chat('/bot spawn');
  log('   spawned:', await waitForChat(/Reporting for duty/, 15000));

  log('== TEST 4: /bot task (build)');
  bot.chat('/bot task Build a 5x5 platform of STONE_BRICKS on the ground a few blocks away from Tester. Keep it simple - just the flat platform, nothing else.');
  lastBotChatAt = Date.now();

  // Wait until the bot has been quiet for 45s (task over) or 5 minutes total.
  const start = Date.now();
  while (Date.now() - start < 300000) {
    await wait(5000);
    if (Date.now() - lastBotChatAt > 45000) break;
  }

  log('== TEST 5: verify placed blocks');
  const mcData = require('minecraft-data')(bot.version);
  const found = bot.findBlocks({
    matching: mcData.blocksByName.stone_bricks.id,
    maxDistance: 48,
    count: 100,
  });
  log('   stone_bricks blocks found near player:', found.length);
  if (found.length > 0) {
    log('   sample positions:', found.slice(0, 5).map((p) => `${p.x},${p.y},${p.z}`).join('  '));
  }

  log('== TEST 6: cleanup');
  bot.chat('/bot status');
  await wait(2000);
  bot.chat('/bot despawn');
  await wait(2000);

  log('== RESULT:', found.length >= 20 ? 'BUILD VERIFIED' : 'BUILD NOT FOUND');
  bot.quit();
  process.exit(0);
});
